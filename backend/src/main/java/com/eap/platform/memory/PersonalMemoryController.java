package com.eap.platform.memory;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/personal-memories")
public class PersonalMemoryController {
    private final PersonalMemoryService memories;
    public PersonalMemoryController(PersonalMemoryService memories) { this.memories = memories; }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") String requestKey,
            @NotBlank @Size(max = 2048) String content,
            Long expiresAtMs,
            @NotNull Boolean explicitlyRemember) {}
    public record Memory(String id, String content, long createdAtMs, Long expiresAtMs, String status) {}
    public record Page(List<Memory> items, String nextCursor) {}
    public record Revocation(boolean revoked) {}

    @GetMapping
    public ApiResponse<Page> list(@RequestParam(required = false) String cursor, Authentication authentication) {
        return ApiResponse.ok(memories.list(authentication, cursor));
    }

    @PostMapping
    public ApiResponse<Memory> create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        return ApiResponse.ok(memories.create(authentication, request));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<Revocation> revoke(@PathVariable String id, Authentication authentication) {
        memories.revoke(authentication, id);
        return ApiResponse.ok(new Revocation(true));
    }
}
