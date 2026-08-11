package com.example.batteryrisk.dto.auth;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 비밀번호 복잡도 규칙. 규제 가이드 ②(비밀번호 규칙 안내/적용) 대응.
 *
 * <p>영문·숫자·특수문자 중
 * <ul>
 *   <li>3종류 조합이면 8~16자</li>
 *   <li>2종류 조합이면 10~16자</li>
 *   <li>1종류뿐이면 불허</li>
 * </ul>
 * 공백과 사용 제한 특수문자({@code ( ) < > " ' ;})는 허용하지 않는다(가이드 제외 문자).
 *
 * <p>애너테이션과 검증기를 한 파일에 둔다(프로젝트 "폴더당 기능 파일 1개" 규칙).
 */
@Documented
@Constraint(validatedBy = ValidPassword.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {
    String message() default
            "비밀번호는 영문·숫자·특수문자 2종 조합 10~16자 또는 3종 조합 8~16자여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidPassword, String> {
        /** 가이드에서 제외한 특수문자. 이 문자가 포함되면 불허한다. */
        private static final String FORBIDDEN_SPECIALS = "()<>\"';";

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            // null 은 @NotBlank 등 다른 제약이 처리하도록 통과시킨다(중복 에러 방지).
            if (value == null) {
                return true;
            }
            int length = value.length();
            boolean hasLetter = false;
            boolean hasDigit = false;
            boolean hasSpecial = false;

            for (int i = 0; i < length; i++) {
                char ch = value.charAt(i);
                if (Character.isWhitespace(ch) || FORBIDDEN_SPECIALS.indexOf(ch) >= 0) {
                    return false;
                }
                if (Character.isLetter(ch)) {
                    hasLetter = true;
                } else if (Character.isDigit(ch)) {
                    hasDigit = true;
                } else {
                    hasSpecial = true;
                }
            }

            int kinds = (hasLetter ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);
            if (kinds >= 3) {
                return length >= 8 && length <= 16;
            }
            if (kinds == 2) {
                return length >= 10 && length <= 16;
            }
            return false;
        }
    }
}
