package com.choculaterie.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ApiException extends RuntimeException {

    private final int status;
    private final String body;

    public ApiException(int status, String body) {
        super("Request failed: " + status + " - " + body);
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public boolean isConflict() {
        return status == 409;
    }

    public String field(String name) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            return o.has(name) && !o.get(name).isJsonNull() ? o.get(name).getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
