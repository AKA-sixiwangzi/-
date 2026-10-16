package com.xuri.training.common;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem p) {
    return ResponseEntity.status(p.status).body(Map.of("message", p.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    jakarta.validation.ConstraintViolationException.class,
    org.apache.poi.ooxml.POIXMLException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("message", "请求参数不合法，请核对必填项及字段范围"));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<?> tooLarge(Exception e) {
    return ResponseEntity.status(413).body(Map.of("message", "上传文件超过大小限制"));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> method(Exception e) {
    return ResponseEntity.status(405).body(Map.of("message", "不支持该请求方法"));
  }

  @ExceptionHandler({
    org.springframework.web.servlet.NoHandlerFoundException.class,
    org.springframework.web.servlet.resource.NoResourceFoundException.class
  })
  ResponseEntity<?> notFound(Exception e) {
    return ResponseEntity.status(404).body(Map.of("message", "接口不存在"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409).body(Map.of("message", "数据冲突，请检查关联关系或重复记录"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    String ref = UUID.randomUUID().toString();
    org.slf4j.LoggerFactory.getLogger(Errors.class).error("request {}", ref, e);
    return ResponseEntity.internalServerError()
        .body(Map.of("message", "服务暂时不可用", "reference", ref));
  }
}
