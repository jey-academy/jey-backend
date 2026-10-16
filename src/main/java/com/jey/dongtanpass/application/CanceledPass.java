package com.jey.dongtanpass.application;

import com.jey.dongtanpass.domain.PassState;

/** 취소한 패스. */
public record CanceledPass(long id, PassState state) {

}
