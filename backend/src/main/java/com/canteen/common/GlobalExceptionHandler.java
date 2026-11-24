package com.canteen.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>修复 P2：服务端异常只写日志，不再把 Jackson 堆栈、SQL、内部消息回显给客户端；
 * 参数绑定/类型转换/校验异常统一返回 400 业务码。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValid(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return Result.error(400, msg.isEmpty() ? "请求参数不合法" : msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        return Result.error(400, e.getConstraintViolations().stream()
                .map(v -> v.getMessage()).collect(Collectors.joining("；")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissing(MissingServletRequestParameterException e) {
        return Result.error(400, "缺少必要参数：" + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.error(400, "参数格式不合法：" + e.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.info("请求体解析失败: {}", e.getMessage());
        return Result.error(400, "请求数据格式不合法，请检查字段类型与日期格式");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return Result.error(400, "上传文件超过大小限制");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicate(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMessage());
        return Result.error(409, "数据已存在，请勿重复提交");
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(NoHandlerFoundException e) {
        return Result.error(404, "接口不存在");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e, HttpServletRequest request) {
        log.error("接口异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        // 不回显内部异常信息，避免泄露 SQL、类名、堆栈
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
