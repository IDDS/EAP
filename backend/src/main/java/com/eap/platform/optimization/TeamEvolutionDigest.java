package com.eap.platform.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Canonical request/output digests shared by dispatch and trusted Gateway evidence. */
public final class TeamEvolutionDigest {
    private TeamEvolutionDigest() {}
    private static JsonNode sorted(ObjectMapper json,JsonNode node) {
        if(node.isObject()) {
            var result=json.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);
            names.forEach(n->result.set(n,sorted(json,node.get(n))));return result;
        }
        if(node.isArray()){var result=json.createArrayNode();node.forEach(n->result.add(sorted(json,n)));return result;}
        return node;
    }
    public static String of(ObjectMapper json,JsonNode node) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(sorted(json,node))));}
        catch(Exception ex){throw new IllegalStateException("TEAM_DIGEST_FAILED");}
    }
}
