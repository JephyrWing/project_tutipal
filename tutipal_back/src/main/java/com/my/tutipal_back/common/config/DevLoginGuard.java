package com.my.tutipal_back.common.config;

import java.util.Arrays;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** local 프로필이 아니거나 prod 프로필이 켜진 상태에서 app.dev-login=true 면 앱을 띄우지 않는다. */
@Component
public class DevLoginGuard {

    public DevLoginGuard(Environment env) {
        if (DevLogin.requested(env) && !DevLogin.allowedProfiles(env)) {
            throw new IllegalStateException("app.dev-login=true is only allowed with the 'local' profile and without 'prod'"
                    + " (active profiles: " + Arrays.toString(env.getActiveProfiles()) + ")");
        }
    }
}
