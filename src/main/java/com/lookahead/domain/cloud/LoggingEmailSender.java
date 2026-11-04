package com.lookahead.domain.cloud;

import com.lookahead.domain.Events;

/**
 * DLV-969 S36: the fallback while {@code app.email.enabled} is false or unset. Logs only that an email was skipped, to a
 * masked address; never the full address or the body (the body carries a single-use link). The line is on
 * {@code lookahead.event} ({@code email.skipped}) so the owner sees in CloudWatch that sending is still off.
 */
public final class LoggingEmailSender implements EmailSender {
    @Override public void send(String to,String subject,String textBody){
        Events.success(Events.EVENT,"email.skipped").addKeyValue("reason","EMAIL_DISABLED")
            .log("Email not sent, app.email.enabled is off (LOOKAHEAD_EMAIL_ENABLED): to {}, subject \"{}\"",mask(to),subject);
    }
    /** {@code paula@gmail.com} becomes {@code p•••a@gmail.com}; anything without a local part and domain is {@code •••}. */
    public static String mask(String address){
        int at=address==null?-1:address.lastIndexOf('@');
        if(at<1||at==address.length()-1)return "•••";
        String local=address.substring(0,at);
        return local.charAt(0)+"•••"+(local.length()>1?local.substring(local.length()-1):"")+address.substring(at);
    }
}
