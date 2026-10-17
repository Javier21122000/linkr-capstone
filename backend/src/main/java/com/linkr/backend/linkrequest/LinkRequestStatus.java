package com.linkr.backend.linkrequest;

/** Stato memorizzato di una richiesta Link. Transizioni ammesse: PENDING -> ACCEPTED, PENDING -> DECLINED. */
public enum LinkRequestStatus {
	PENDING,
	ACCEPTED,
	DECLINED
}
