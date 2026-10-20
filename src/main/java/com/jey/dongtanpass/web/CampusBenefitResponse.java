package com.jey.dongtanpass.web;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

record CampusBenefitResponse(@Schema(description = "할인율. 5%는 0.05. 발급할 때 이 값이 패스에 남는다",
		requiredMode = RequiredMode.REQUIRED) BigDecimal discountRate) {
}
