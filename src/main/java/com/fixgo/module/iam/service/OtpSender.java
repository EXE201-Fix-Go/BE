package com.fixgo.module.iam.service;
import com.fixgo.module.iam.entity.*;
import com.fixgo.module.iam.enums.*;
import com.fixgo.module.iam.dto.*;
import com.fixgo.module.iam.repository.*;
import com.fixgo.module.iam.service.*;


/** Delivery channel for OTP codes. The SMS provider is not chosen yet (BRD §8), so the default only logs. */
public interface OtpSender {
    void send(String phoneE164, String code);
}
