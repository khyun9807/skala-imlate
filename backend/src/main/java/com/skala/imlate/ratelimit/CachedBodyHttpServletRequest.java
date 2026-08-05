package com.skala.imlate.ratelimit;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * 요청 본문을 메모리에 담아 <b>여러 번 읽을 수 있게</b> 만드는 래퍼.
 *
 * <p>서블릿 본문 스트림은 한 번만 읽을 수 있다. 그래서 rate limit 이 본문(반·이름·호수)을 보려면
 * 컨트롤러가 다시 읽을 수 있도록 미리 버퍼링해 두어야 한다.
 *
 * <p>스프링의 {@code ContentCachingRequestWrapper} 를 쓰지 않은 이유 — 그 클래스는 "읽는 김에 복사본을
 * 남기는" 구조라 <u>다시 읽을 수는 없다</u>(사후 로깅용이다). 여기서는 재읽기가 목적이므로
 * 생성 시점에 통째로 담아 두고 {@link #getInputStream()} 호출마다 새 스트림을 돌려준다.
 *
 * <p>본문은 {@link #BODY_ATTRIBUTE} 요청 속성으로도 노출한다. 중간에 다른 필터가 요청을 한 번 더 감싸도
 * 인터셉터가 캐스팅 없이 본문을 꺼낼 수 있다.
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    /** 캐시된 본문(byte[])이 담기는 요청 속성 이름. */
    static final String BODY_ATTRIBUTE = CachedBodyHttpServletRequest.class.getName() + ".BODY";

    private final byte[] body;

    /**
     * @param request 원본 요청
     * @param body    이미 다 읽어 둔 본문(null 아님)
     */
    CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
        request.setAttribute(BODY_ATTRIBUTE, body);
    }

    /**
     * 요청에 캐시된 본문을 꺼낸다.
     *
     * @param request 현재 요청(null 허용)
     * @return 캐시된 본문. 캐싱되지 않았으면 null
     */
    static byte[] cachedBody(ServletRequest request) {
        if (request == null) {
            return null;
        }
        Object cached = request.getAttribute(BODY_ATTRIBUTE);
        return cached instanceof byte[] bytes ? bytes : null;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {

            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] buffer, int off, int len) {
                return source.read(buffer, off, len);
            }

            @Override
            public int available() {
                return source.available();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // 본문이 이미 메모리에 있으므로 비동기 읽기 콜백은 의미가 없다.
                throw new UnsupportedOperationException("캐시된 본문에는 비동기 읽기를 사용할 수 없다.");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset()));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    private Charset charset() {
        String encoding = getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException e) {
            // 잘못된 인코딩 이름 때문에 요청이 실패하면 안 된다.
            return StandardCharsets.UTF_8;
        }
    }
}
