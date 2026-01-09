package com.jingyicare.jingyi_icis_engine.service.users;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class Encoder {
    public Encoder(@Value("${jingyi.passwordencoder.bcrypt-strength}") int bcryptStrength) {
        this.encoder = new BCryptPasswordEncoder(bcryptStrength);
    }

    public BCryptPasswordEncoder get() {
        return encoder;
    }

    private BCryptPasswordEncoder encoder;
}