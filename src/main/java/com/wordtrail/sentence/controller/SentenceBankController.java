package com.wordtrail.sentence.controller;

import com.wordtrail.common.response.*;
import com.wordtrail.sentence.dto.*;
import com.wordtrail.sentence.service.*;
import com.wordtrail.sentence.vo.SentenceBankVO.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/sentence-translation/management") @RequiredArgsConstructor
public class SentenceBankController {
    private final SentenceBankService service;
    private final SentenceEditorAccess access;
    @GetMapping("/access") public ApiResponse<Access> access(){return ApiResponse.ok(new Access(access.allowed()));}
    @GetMapping("/questions") public ApiResponse<PageVO<ManagedQuestion>> list(@Valid @ModelAttribute SentenceManagementQuery q){return ApiResponse.ok(service.list(q));}
    @GetMapping("/questions/{id}") public ApiResponse<ManagedQuestion> detail(@PathVariable @Positive long id){return ApiResponse.ok(service.detail(id));}
    @PostMapping("/questions") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ManagedQuestion> create(@Valid @RequestBody SentenceQuestionWriteRequest r){return ApiResponse.ok(service.create(r));}
    @PutMapping("/questions/{id}") public ApiResponse<ManagedQuestion> update(@PathVariable @Positive long id,@Valid @RequestBody SentenceQuestionWriteRequest r){return ApiResponse.ok(service.update(id,r));}
    @PostMapping(value="/import/preview",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Preview> preview(@Valid @RequestBody SentenceImportRequest r){return ApiResponse.ok(service.preview(r.text()));}
    @PostMapping(value="/import/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Preview> file(@RequestParam("file") MultipartFile file){return ApiResponse.ok(service.previewFile(file));}
    @PostMapping("/import") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Imported> confirm(@Valid @RequestBody SentenceImportRequest r){return ApiResponse.ok(service.confirm(r));}
}
