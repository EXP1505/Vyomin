package com.vyomin.core_api.dto;

public record AuthResponse(String token, String email, String name, String pictureUrl) {
}
