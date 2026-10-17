-- A managed Local test account must be able to erase its synthetic purchases
-- after Identity disables the login. V12 granted the audit and mock-payment
-- deletes but omitted these dependent purchase tables for the runtime role.
GRANT DELETE ON membership_payment_attempts,membership_provider_events,membership_purchases
    TO lookahead_platform_app;
