package com.linkr.backend.ticketmaster;

/**
 * Perche' un evento di Ticketmaster NON diventa candidato. Per ogni evento si registra un solo motivo, il primo che si verifica
 * nell'ordine: evento di test / dati essenziali mancanti / prodotto che non e' una singola uscita, stato, data, coordinate, categoria (cosi' i totali del report tornano).
 */
enum ExclusionReason {

	TEST_EVENT(Group.OTHER),
	MISSING_ID_OR_TITLE(Group.OTHER),
	/** Abbonamento, pacchetto, parcheggio, voucher...: un prodotto che non rappresenta una singola uscita. */
	NOT_SINGLE_EVENT(Group.OTHER),
	/** Stesso id gia' visto in questa esecuzione (la paginazione puo' ripetere un evento). */
	DUPLICATE_EXTERNAL_ID(Group.OTHER),
	/** Evento di forma inattesa nella risposta: non letto. */
	UNREADABLE(Group.OTHER),

	STATUS_CANCELLED(Group.STATUS),
	/** Rinviato: non si pubblica in automatico (data non piu' valida). */
	STATUS_POSTPONED(Group.STATUS),
	/** Ripianificato: non si pubblica in automatico finche' la nuova data non e' verificata. */
	STATUS_RESCHEDULED(Group.STATUS),
	STATUS_UNKNOWN(Group.STATUS),

	DATE_MISSING(Group.DATE),
	/** Data/ora da definire (TBD/TBA) o non interpretabile. */
	DATE_UNRELIABLE(Group.DATE),
	/** Data UTC e data+ora locale con fuso non coincidono. */
	DATE_INCONSISTENT(Group.DATE),
	DATE_PAST(Group.DATE),
	DATE_BEYOND_WINDOW(Group.DATE),

	COORDINATES_MISSING(Group.COORDINATES),
	COORDINATES_INVALID(Group.COORDINATES),
	OUTSIDE_AREA(Group.COORDINATES),

	/** Segmento non riconosciuto (o assente): escluso e contato per nome nel report. */
	CATEGORY_UNRECOGNIZED(Group.CATEGORY);

	enum Group {
		STATUS, DATE, COORDINATES, CATEGORY, OTHER
	}

	private final Group group;

	ExclusionReason(Group group) {
		this.group = group;
	}

	Group group() {
		return group;
	}

}
