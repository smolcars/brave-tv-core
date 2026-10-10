package org.chromium.chrome.browser.tv;

public class TvRemoteSessionTest {
    public static void main(String[] args) {
        TvRemoteSession session = new TvRemoteSession(0);
        assert session.invitation.length() == 43;
        assert !session.authenticate("", 0);
        assert !session.acceptSequence(1);
        String token = session.pair(session.invitation, 1);
        assert !token.isEmpty() && !token.equals(session.invitation);
        assert session.pair(session.invitation, 2).isEmpty();
        assert session.pending() && !session.approved();
        assert session.authenticate(token, 2);
        assert !session.authenticate("wrong", 2);
        session.approve();
        assert session.acceptSequence(1);
        assert !session.acceptSequence(1) && !session.acceptSequence(0);
        assert session.acceptSequence(3);
        assert session.expired(120003);
        session.revoke();
        assert !session.authenticate(token, 3);
        TvRemoteSession expired = new TvRemoteSession(0);
        assert expired.pair(expired.invitation, 120000).isEmpty();
        TvRemoteSession limited = new TvRemoteSession(0);
        for (int i=0;i<5;i++) assert limited.pair("invalid", 1).isEmpty();
        assert limited.pair(limited.code, 2).isEmpty();
        TvRemoteSession manual = new TvRemoteSession(0);
        assert !manual.pair(manual.code, 1).isEmpty();
        manual.approve();
        assert manual.expired(35*60000L);
        System.out.println("Session security checks passed");
    }
}
