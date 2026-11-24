package com.canteen.controller;

import com.canteen.common.BizException;
import com.canteen.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 文件上传与受控下载。
 *
 * <p>修复 P0-3 / P0-4：接口纳入登录鉴权、取消公开静态目录映射；
 * 目录名做白名单 + canonical path 双校验，杜绝 ../ 穿越；补魔术字节校验。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private static final Pattern TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{0,19}$");
    private static final Pattern FILE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,120}$");

    @Value("${canteen.upload.dir:./uploads}")
    private String uploadDir;

    @Value("${canteen.upload.allow-ext:jpg,jpeg,png,pdf,webp}")
    private String allowExt;

    @Value("${canteen.upload.max-size-mb:5}")
    private long maxSizeMb;

    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(defaultValue = "common") String type) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要上传的文件");
        }
        if (!TYPE_PATTERN.matcher(type).matches()) {
            throw new BizException(400, "上传目录名不合法（仅允许小写字母、数字、下划线与短横线）");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        List<String> allow = List.of(allowExt.split(","));
        if (!allow.contains(ext)) {
            throw new BizException(400, "仅支持上传 " + allowExt + " 格式的文件");
        }
        if (file.getSize() > maxSizeMb * 1024 * 1024) {
            throw new BizException(400, "文件大小不能超过 " + maxSizeMb + "MB");
        }
        try {
            byte[] head = file.getInputStream().readNBytes(12);
            if (!magicMatches(ext, head)) {
                throw new BizException(400, "文件内容与扩展名不符，请上传真实文件");
            }
            File dir = safeChild(uploadDir, type);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new BizException("上传目录创建失败");
            }
            String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            file.transferTo(new File(dir, fileName).getAbsoluteFile());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", "/api/files/" + type + "/" + fileName);
            m.put("name", name);
            m.put("size", file.getSize());
            return Result.success("上传成功", m);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("文件上传失败: {}", e.getMessage());
            throw new BizException("文件上传失败，请稍后重试");
        }
    }

    /** 受控下载：必须登录，且路径被严格限制在 uploads 根目录内。 */
    @GetMapping("/{type}/{filename}")
    public ResponseEntity<Resource> download(@PathVariable String type, @PathVariable String filename) {
        if (!TYPE_PATTERN.matcher(type).matches() || !FILE_PATTERN.matcher(filename).matches()
                || filename.contains("..")) {
            throw new BizException(400, "文件路径不合法");
        }
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        List<String> allow = List.of(allowExt.split(","));
        if (!allow.contains(ext)) {
            throw new BizException(400, "文件类型不允许访问");
        }
        File target = safeChild(new File(uploadDir, type).getPath(), filename);
        if (!target.isFile()) {
            throw new BizException(404, "文件不存在");
        }
        String contentType = probeContentType(target.toPath(), ext);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new FileSystemResource(target));
    }

    /** 基于 canonical path 的目录约束，任何 ../ 或绝对路径都逃不出去。 */
    private File safeChild(String parent, String child) {
        try {
            File root = new File(uploadDir).getCanonicalFile();
            File dir = new File(parent).getCanonicalFile();
            if (!dir.toPath().startsWith(root.toPath())) {
                throw new BizException(400, "上传目录越界");
            }
            File f = new File(dir, child).getCanonicalFile();
            if (!f.toPath().startsWith(root.toPath()) || !f.toPath().startsWith(dir.toPath())) {
                throw new BizException(400, "文件路径越界");
            }
            return f;
        } catch (java.io.IOException e) {
            throw new BizException(400, "文件路径解析失败");
        }
    }

    private boolean magicMatches(String ext, byte[] head) {
        if (head == null || head.length == 0) {
            return false;
        }
        return switch (ext) {
            case "jpg", "jpeg" -> head.length >= 3
                    && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "png" -> head.length >= 8 && head[0] == (byte) 0x89 && head[1] == 0x50
                    && head[2] == 0x4E && head[3] == 0x47 && head[4] == 0x0D && head[5] == 0x0A
                    && head[6] == 0x1A && head[7] == 0x0A;
            case "pdf" -> head.length >= 4 && new String(head, 0, 4, StandardCharsets.US_ASCII).equals("%PDF");
            case "webp" -> head.length >= 12
                    && new String(head, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(head, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            default -> false;
        };
    }

    private String probeContentType(Path path, String ext) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null) {
                return probed;
            }
        } catch (Exception ignored) {
            // 退回按扩展名推断
        }
        return switch (ext) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG_VALUE;
            case "png" -> MediaType.IMAGE_PNG_VALUE;
            case "webp" -> "image/webp";
            case "pdf" -> MediaType.APPLICATION_PDF_VALUE;
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }
}
