package com.eap.platform.management;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/management/connectors")
public class ManagementConnectorController {
    private final ManagementConnectorService service;

    public ManagementConnectorController(ManagementConnectorService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ManagementConnectorDtos.ConnectorResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<ManagementConnectorDtos.ConnectorResponse> create(
            @Valid @RequestBody ManagementConnectorDtos.ConnectorRequest request
    ) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ManagementConnectorDtos.ConnectorResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ManagementConnectorDtos.ConnectorRequest request
    ) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<ManagementConnectorDtos.ConnectorResponse> setEnabled(
            @PathVariable Long id,
            @RequestParam boolean enabled
    ) {
        return ApiResponse.ok(service.setEnabled(id, enabled));
    }

    @PostMapping("/{id}/sync")
    public ApiResponse<ManagementConnectorDtos.SyncResult> sync(@PathVariable Long id) {
        return ApiResponse.ok(service.sync(id));
    }
}
