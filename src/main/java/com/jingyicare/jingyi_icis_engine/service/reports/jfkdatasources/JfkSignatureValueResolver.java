package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkImageUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

public class JfkSignatureValueResolver {
    public JfkSignatureValueResolver(
        AccountRepository accountRepo,
        Collection<String> accountRefs,
        Logger log,
        String logContext
    ) {
        this.log = log;
        this.logContext = logContext;
        this.accountByRef = loadAccounts(accountRepo, accountRefs);
    }

    public String signatureOrFallback(String accountRef, String fallbackText, Object sourceId) {
        String fallback = fallbackText == null ? "" : fallbackText;
        if (StrUtils.isBlank(accountRef)) {
            return fallback;
        }
        Long accountId = parseAccountId(accountRef);
        if (accountId == null || accountId <= 0) {
            log.warn("{} signature account id invalid, sourceId={}, accountRef={}", logContext, sourceId, accountRef);
            return fallback;
        }
        Account account = accountByRef.get(accountRef);
        if (account == null) {
            log.warn("{} signature account not found, sourceId={}, accountId={}", logContext, sourceId, accountId);
            return fallback;
        }
        String signPic = account.getSignPic();
        if (StrUtils.isBlank(signPic)) {
            return fallback;
        }
        if (!JfkImageUtils.isSupportedImageValue(signPic)) {
            log.warn("{} signature image unsupported, sourceId={}, accountId={}", logContext, sourceId, accountId);
            return fallback;
        }
        return signPic;
    }

    private Map<String, Account> loadAccounts(AccountRepository accountRepo, Collection<String> accountRefs) {
        if (accountRepo == null || accountRefs == null || accountRefs.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> accountIdByRef = new LinkedHashMap<>();
        for (String accountRef : accountRefs) {
            if (StrUtils.isBlank(accountRef)) continue;
            Long accountId = parseAccountId(accountRef);
            if (accountId == null || accountId <= 0) continue;
            accountIdByRef.putIfAbsent(accountRef, accountId);
        }
        if (accountIdByRef.isEmpty()) {
            return Map.of();
        }
        List<Long> accountIds = new ArrayList<>(new LinkedHashSet<>(accountIdByRef.values()));
        Map<Long, Account> accountById = new LinkedHashMap<>();
        for (Account account : accountRepo.findByIdInAndIsDeletedFalse(accountIds)) {
            if (account == null || account.getId() == null) continue;
            accountById.putIfAbsent(account.getId(), account);
        }
        Map<String, Account> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : accountIdByRef.entrySet()) {
            Account account = accountById.get(entry.getValue());
            if (account != null) {
                result.put(entry.getKey(), account);
            }
        }
        return result;
    }

    private Long parseAccountId(String accountRef) {
        try {
            return Long.parseLong(accountRef);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private final Map<String, Account> accountByRef;
    private final Logger log;
    private final String logContext;
}
