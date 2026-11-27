package com.lookahead.domain.dto;

import java.util.List;

/** The body of a DEV admission: the waiting account and the roles it gets. DLV-1016: was nested in DevAccessService. */
public record DevAccessAdmission(String accountId,List<String> roles) {}
