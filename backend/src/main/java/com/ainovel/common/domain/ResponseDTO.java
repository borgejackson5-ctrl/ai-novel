package com.ainovel.common.domain;

import com.ainovel.common.code.ErrorCode;
import lombok.Data;

/**
 * 统一响应包装
 */
@Data
public class ResponseDTO<T> {

    private Integer code;
    private String msg;
    private T data;
    private Boolean success;

    public static <T> ResponseDTO<T> ok() {
        ResponseDTO<T> dto = new ResponseDTO<>();
        dto.setCode(ErrorCode.OK.getCode());
        dto.setMsg(ErrorCode.OK.getMsg());
        dto.setSuccess(true);
        return dto;
    }

    public static <T> ResponseDTO<T> ok(T data) {
        ResponseDTO<T> dto = ok();
        dto.setData(data);
        return dto;
    }

    public static <T> ResponseDTO<T> ok(T data, String msg) {
        ResponseDTO<T> dto = ok(data);
        dto.setMsg(msg);
        return dto;
    }

    public static <T> ResponseDTO<T> error(ErrorCode errorCode) {
        ResponseDTO<T> dto = new ResponseDTO<>();
        dto.setCode(errorCode.getCode());
        dto.setMsg(errorCode.getMsg());
        dto.setSuccess(false);
        return dto;
    }

    public static <T> ResponseDTO<T> error(ErrorCode errorCode, String msg) {
        ResponseDTO<T> dto = error(errorCode);
        dto.setMsg(msg);
        return dto;
    }
}
