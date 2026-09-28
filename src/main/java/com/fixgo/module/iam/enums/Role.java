package com.fixgo.module.iam.enums;
import com.fixgo.module.iam.entity.*;
import com.fixgo.module.iam.enums.*;
import com.fixgo.module.iam.dto.*;
import com.fixgo.module.iam.repository.*;
import com.fixgo.module.iam.service.*;


/** Coarse role stored on app_users (ERD §4). Partner sub-types live in partner_profiles.partner_type. */
public enum Role { CUSTOMER, PARTNER, ADMIN }
