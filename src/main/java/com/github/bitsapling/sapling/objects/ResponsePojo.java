package com.github.bitsapling.sapling.objects;

import lombok.Getter;

import java.io.Serial;
import java.io.Serializable;

@Getter
public abstract class ResponsePojo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private final long code;

    protected ResponsePojo(long code) {
        this.code = code;
    }

    public long getCode() {
        return code;
    }
}
