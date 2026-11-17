package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ca.CaSignatureImageService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

class JfkSignatureValueResolverTests {
    @Test
    void keepsEachRowsTextAndClearsTheCacheAfterAnInterruptedReport() {
        CaSignatureImageService ca = mock(CaSignatureImageService.class);
        JfkSignatureValueResolver signatures = new JfkSignatureValueResolver(
            mock(AccountRepository.class), ca);
        when(ca.getPng(55L)).thenReturn(new Pair<>(false, new byte[0]));

        assertThatThrownBy(() -> signatures.withCompactSignatures(() -> {
            assertThat(signatures.resolve("55", "甲", Map.of()))
                .isEqualTo(new JfkSignatureValueResolver.Signature("", "甲"));
            assertThat(signatures.resolve("55", "乙", Map.of()).displayValue()).isEqualTo("乙");
            throw new IllegalStateException("取数中断");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(signatures.resolve("56", "丙", Map.of()).displayValue()).isEqualTo("丙");
        verify(ca).getPng(55L);
        verifyNoMoreInteractions(ca);
    }
}
