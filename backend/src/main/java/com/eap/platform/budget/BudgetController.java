package com.eap.platform.budget;
import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/budgets")
public class BudgetController {
    private final BudgetLedger ledger;
    public BudgetController(BudgetLedger ledger){this.ledger=ledger;}
    @GetMapping public ApiResponse<BudgetLedger.Dashboard> dashboard(){return ApiResponse.ok(ledger.dashboard());}
    @PutMapping("/accounts") public ApiResponse<BudgetLedger.Account> limits(@Valid @RequestBody BudgetLedger.Limit request,Authentication actor){return ApiResponse.ok(ledger.setLimit(request,actor==null?"console":actor.getName()));}
}
