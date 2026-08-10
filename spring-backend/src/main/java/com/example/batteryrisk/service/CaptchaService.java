package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.auth.CaptchaResponse;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 자체 이미지 캡챠. 규제 가이드 제4조 접근통제(캡챠) 대응.
 *
 * <p>reCAPTCHA 같은 외부 키 없이 java.awt로 왜곡·노이즈가 섞인 문자 이미지를 만든다.
 * 발급본은 인메모리 TTL 맵에 담고 검증 시 1회용으로 제거한다. 단일 인스턴스 전제이며,
 * 재기동 시 유실돼도 무방한 데이터라 별도 저장소를 두지 않는다(TokenBlacklist와 성격이 다르다).
 */
@Service
public class CaptchaService {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int CODE_LENGTH = 5;
    // 사람이 혼동하기 쉬운 문자(0/O, 1/I/L 등)를 뺀 코드 문자 집합.
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int WIDTH = 160;
    private static final int HEIGHT = 60;

    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /** 새 캡챠를 발급한다. 만료분은 발급 시점에 청소한다. */
    public CaptchaResponse issue() {
        purgeExpired();
        String code = randomCode();
        String id = UUID.randomUUID().toString();
        store.put(id, new Entry(code, Instant.now().plus(TTL)));
        return new CaptchaResponse(id, toDataUrl(render(code)), TTL.getSeconds());
    }

    /**
     * 입력값이 발급된 캡챠와 일치하는지 검증한다(대소문자 무시). 1회용이라 검증 후 즉시 제거한다.
     * 만료·미존재·불일치는 모두 false.
     */
    public boolean validate(String captchaId, String answer) {
        if (captchaId == null || answer == null) {
            return false;
        }
        Entry entry = store.remove(captchaId);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return false;
        }
        return entry.code().equalsIgnoreCase(answer.trim());
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }

    private BufferedImage render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(245, 247, 250));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // 배경 노이즈 라인.
        for (int i = 0; i < 8; i++) {
            g.setColor(new Color(random.nextInt(180) + 40, random.nextInt(180) + 40, random.nextInt(180) + 40));
            g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT), random.nextInt(WIDTH), random.nextInt(HEIGHT));
        }

        // 문자마다 약간씩 회전·색을 달리해 OCR을 방해한다.
        int x = 18;
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(random.nextInt(120), random.nextInt(120), random.nextInt(120)));
            g.setFont(new Font("SansSerif", Font.BOLD, 34 + random.nextInt(6)));
            double angle = (random.nextDouble() - 0.5) * 0.6;
            g.rotate(angle, x, HEIGHT / 2.0);
            g.drawString(String.valueOf(code.charAt(i)), x, 40 + random.nextInt(6));
            g.rotate(-angle, x, HEIGHT / 2.0);
            x += 26;
        }

        // 점 노이즈.
        for (int i = 0; i < 40; i++) {
            g.setColor(new Color(random.nextInt(200), random.nextInt(200), random.nextInt(200)));
            g.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 2, 2);
        }
        g.dispose();
        return image;
    }

    private static String toDataUrl(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException exception) {
            throw new UncheckedIOException("캡챠 이미지를 생성하지 못했습니다.", exception);
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private record Entry(String code, Instant expiresAt) {}
}
