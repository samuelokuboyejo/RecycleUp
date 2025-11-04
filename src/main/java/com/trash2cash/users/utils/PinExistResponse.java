package com.trash2cash.users.utils;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class PinExistResponse {
    private boolean pinExist;
}
