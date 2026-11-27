package com.my.tutipal_back;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.my.tutipal_back.materials.service.MaterialOpsCommand;

@ConfigurationPropertiesScan
@SpringBootApplication
@EnableScheduling
public class TutipalBackApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("material-ops")) {
            System.exit(MaterialOpsCommand.run(args, System.getenv(), System.out, System.err));
        }
        SpringApplication.run(TutipalBackApplication.class, args);
    }
}
