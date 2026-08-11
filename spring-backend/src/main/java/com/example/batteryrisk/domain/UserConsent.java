package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 회원가입 시 개인정보 수집·이용 동의 이력. 규제 가이드 ①(회원가입 시 개인정보 수집 및 이용 동의) 대응.
 *
 * <p>필수/선택 동의를 각각 한 행으로 남겨 동의 유형·여부·시각을 감사 추적한다.
 * users 와는 user_id 로만 연결한다(단순 이력 저장 목적이라 JPA 연관관계는 두지 않는다).
 */
@Entity
@Table(name = "user_consents")
public class UserConsent {

    /** 동의 유형. DB CHECK 제약(ck_user_consents_type)과 동일하게 유지한다. */
    public enum ConsentType {
        PRIVACY_REQUIRED,
        MARKETING_OPTIONAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 40)
    private ConsentType consentType;

    @Column(nullable = false)
    private boolean agreed;

    @Column(name = "agreed_at", nullable = false)
    private OffsetDateTime agreedAt;

    protected UserConsent() {
    }

    public UserConsent(Long userId, ConsentType consentType, boolean agreed) {
        this.userId = userId;
        this.consentType = consentType;
        this.agreed = agreed;
        this.agreedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public ConsentType getConsentType() {
        return consentType;
    }

    public boolean isAgreed() {
        return agreed;
    }

    public OffsetDateTime getAgreedAt() {
        return agreedAt;
    }
}
