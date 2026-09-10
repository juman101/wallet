package com.paytm.wallet.transfer;

import com.paytm.wallet.common.exceptions.InvalidRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(@RequestBody TransferRequest request) {
        Transfer transfer = transferService.createTransfer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TransferResponse.from(transfer));
    }

    @GetMapping("/{id}")
    public TransferResponse getById(@PathVariable("id") String id) {
        UUID transferId = parseUuid(id);
        return TransferResponse.from(transferService.getTransfer(transferId));
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Not a valid transfer id: " + value);
        }
    }
}
