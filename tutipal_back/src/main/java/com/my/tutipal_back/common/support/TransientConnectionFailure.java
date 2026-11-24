package com.my.tutipal_back.common.support;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import javax.net.ssl.SSLException;

public final class TransientConnectionFailure {

    private TransientConnectionFailure() {
    }

    public static boolean confirmed(Throwable failure) {
        boolean connectionFailure = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException
                    || cause instanceof SSLException) {
                return false;
            }
            connectionFailure |= cause instanceof ConnectException || cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException;
        }
        return connectionFailure;
    }
}
