package com.myblogbackend.blog.request;


import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class LogOutRequest {

    @Valid
    @NotNull(message = "Device info cannot be null")
    private DeviceInfoRequest deviceInfo;

    @Valid
    @NotNull(message = "Existing Token needs to be passed")
    private String token;
}
