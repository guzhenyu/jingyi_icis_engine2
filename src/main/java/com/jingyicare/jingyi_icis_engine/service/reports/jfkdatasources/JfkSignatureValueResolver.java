package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ca.CaSignatureImageService;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkImageUtils;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

@Component
public class JfkSignatureValueResolver {
    public JfkSignatureValueResolver(AccountRepository accountRepo, CaSignatureImageService ca) {
        this.accountRepo = accountRepo;
        this.ca = ca;
    }

    public record Signature(String image, String text) {
        public String displayValue() {
            return image.isEmpty() ? text : image;
        }
    }

    // compact 在当前线程同步取完各表的数据。同份报表复用查图结果，普通 JFK 只取本地图。
    public <T> T withCompactSignatures(Supplier<T> loadDataSources) {
        Map<Long, String> previous = compactImages.get();
        compactImages.set(new HashMap<>());
        try {
            return loadDataSources.get();
        } finally {
            if (previous == null) {
                compactImages.remove();
            } else {
                compactImages.set(previous);
            }
        }
    }

    public Map<Long, Account> loadAccounts(Collection<String> accountRefs) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String ref : accountRefs) {
            long id = parseAccountId(ref);
            if (id > 0) {
                ids.add(id);
            }
        }
        Map<Long, Account> accounts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Account account : accountRepo.findByIdInAndIsDeletedFalse(new ArrayList<>(ids))) {
                accounts.put(account.getId(), account);
            }
        }
        return accounts;
    }

    public Signature resolve(String accountRef, String recordedName, Map<Long, Account> accounts) {
        long id = parseAccountId(accountRef);
        return signature(id, accounts.get(id), recordedName == null ? "" : recordedName);
    }

    // 没有保存记录姓名的表使用当前账号姓名，无法匹配时沿用原记录的账号标识。
    public Signature resolve(String accountRef, Map<Long, Account> accounts) {
        if (StrUtils.isBlank(accountRef)) {
            return new Signature("", "");
        }
        long id = parseAccountId(accountRef);
        Account account = accounts.get(id);
        String name = account == null ? null : account.getName();
        return signature(id, account, StrUtils.isBlank(name) ? accountRef : name);
    }

    private Signature signature(long id, Account account, String text) {
        Map<Long, String> images = compactImages.get();
        if (images != null && id > 0) {
            String image = images.computeIfAbsent(id, this::loadCaImage);
            if (!image.isEmpty()) {
                return new Signature(image, text);
            }
        }
        String local = account == null ? null : account.getSignPic();
        return new Signature(JfkImageUtils.isSupportedImageValue(local) ? local : "", text);
    }

    private String loadCaImage(long accountId) {
        Pair<Boolean, byte[]> png = ca.getPng(accountId);
        return png.getFirst() ? "data:image/png;base64,"
            + Base64.getEncoder().encodeToString(png.getSecond()) : "";
    }

    private long parseAccountId(String ref) {
        if (StrUtils.isBlank(ref)) {
            return 0;
        }
        try {
            long id = Long.parseLong(ref);
            return id > 0 ? id : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private final AccountRepository accountRepo;
    private final CaSignatureImageService ca;
    private final ThreadLocal<Map<Long, String>> compactImages = new ThreadLocal<>();
}
