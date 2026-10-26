package com.canteen.controller;

import com.canteen.common.BizException;
import com.canteen.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传（后缀白名单 + 大小限制）
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

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
            throw new BizException("请选择要上传的文件");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        List<String> allow = List.of(allowExt.split(","));
        if (!allow.contains(ext)) {
            throw new BizException("仅支持上传 " + allowExt + " 格式的文件");
        }
        if (file.getSize() > maxSizeMb * 1024 * 1024) {
            throw new BizException("文件大小不能超过 " + maxSizeMb + "MB");
        }
        try {
            File dir = new File(uploadDir, type);
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
        } catch (Exception e) {
            throw new BizException("文件上传失败：" + e.getMessage());
        }
    }
}
