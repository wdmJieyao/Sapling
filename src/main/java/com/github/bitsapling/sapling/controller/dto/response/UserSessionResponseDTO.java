package com.github.bitsapling.sapling.controller.dto.response;

import cn.dev33.satoken.stp.SaTokenInfo;
import com.github.bitsapling.sapling.objects.ResponsePojo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.validation.annotation.Validated;

@EqualsAndHashCode(callSuper = true)
@Data
@Validated
public class UserSessionResponseDTO extends ResponsePojo {
    private SaTokenInfo token;
    private UserResponseDTO user;

    public UserSessionResponseDTO(SaTokenInfo token, UserResponseDTO user) {
        this.token = token;
        this.user = user;
    }
}
