package com.my.tutipal_back.materials.service;

import java.io.PrintStream;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class MaterialOpsCommand {
    private static final Set<String> OPERATIONS = Set.of("list", "cleanup", "mark-missing", "restore");

    private MaterialOpsCommand() {}

    public static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
        try {
            if (args.length != 3 || !OPERATIONS.contains(args[1])) {
                throw new IllegalArgumentException(
                        "Usage: material-ops list <session-id> | cleanup <candidate-id> | mark-missing <material-id> | restore <material-id>");
            }
            long id = Long.parseLong(args[2]);
            if (id <= 0) {
                throw new IllegalArgumentException("ID must be positive");
            }
            String mode = required(environment, "MATERIAL_STORAGE");
            String root = mode.equals("local") ? required(environment, "MATERIAL_LOCAL_PATH") : ".";
            String bucket = mode.equals("s3") ? required(environment, "S3_BUCKET") : "";
            MaterialObjectStore objects = new MaterialObjectStore(
                    mode, root, bucket, environment.getOrDefault("AWS_REGION", "ap-northeast-2"));
            var dataSource = new DriverManagerDataSource(required(environment, "DB_URL"),
                    required(environment, "DB_USER"), required(environment, "DB_PW"));
            var reconciliation = new MaterialReconciliation(new JdbcTemplate(dataSource), objects,
                    new DataSourceTransactionManager(dataSource));
            return execute(reconciliation, args[1], id, out);
        } catch (NumberFormatException ex) {
            err.println("material-ops: ID must be a positive integer");
            return 2;
        } catch (IllegalArgumentException ex) {
            err.println("material-ops: " + ex.getMessage());
            return 2;
        } catch (RuntimeException ex) {
            err.println("material-ops: " + ex.getClass().getSimpleName());
            return 1;
        }
    }

    static int execute(MaterialReconciliation reconciliation, String operation, long id, PrintStream out) {
        if (operation.equals("list")) {
            out.println("type\tid\tstate\tdetail");
            for (var candidate : reconciliation.cleanupCandidates(id)) {
                out.printf("cleanup\t%d\t%s\t%s %s%n", candidate.id(), candidate.reason(), candidate.key(),
                        candidate.lastError() == null ? "PENDING" : candidate.lastError());
            }
            for (var candidate : reconciliation.originalCandidates(id)) {
                out.printf("original\t%d\t%s\t%s%n", candidate.id(), candidate.presence(),
                        candidate.hidden() ? "HIDDEN" : "");
            }
            return 0;
        }
        var result = switch (operation) {
            case "cleanup" -> reconciliation.cleanup(id);
            case "mark-missing" -> reconciliation.markMissing(id);
            case "restore" -> reconciliation.restore(id);
            default -> throw new IllegalArgumentException("Unknown material operation");
        };
        out.println(result);
        return switch (result) {
            case DONE, NO_CHANGE -> 0;
            default -> 1;
        };
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
