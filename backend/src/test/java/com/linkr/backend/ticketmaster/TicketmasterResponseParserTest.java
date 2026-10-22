package com.linkr.backend.ticketmaster;

import static com.linkr.backend.ticketmaster.TicketmasterTestData.event;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linkr.backend.ticketmaster.TicketmasterDto.EventsPage;
import com.linkr.backend.ticketmaster.TicketmasterException.Kind;

class TicketmasterResponseParserTest {

	@Test
	void readsEventsAndPagingInfo() {
		EventsPage result = TicketmasterResponseParser.parse(page(2, 5, 230, event("a").json(), event("b").json()));

		assertThat(result.events()).extracting(TicketmasterDto.Event::id).containsExactly("a", "b");
		assertThat(result.unreadableEvents()).isZero();
		assertThat(result.page().number()).isEqualTo(2);
		assertThat(result.page().totalPages()).isEqualTo(5);
		assertThat(result.page().totalElements()).isEqualTo(230);
	}

	@Test
	void noResultsHasNoEmbeddedBlockAndIsNotAnError() {
		EventsPage result = TicketmasterResponseParser.parse("{\"page\":{\"size\":50,\"totalElements\":0,\"totalPages\":0,\"number\":0}}");

		assertThat(result.events()).isEmpty();
		assertThat(result.page().totalElements()).isZero();
	}

	@Test
	void missingPageBlockIsTolerated() {
		EventsPage result = TicketmasterResponseParser.parse("{\"_embedded\":{\"events\":[]}}");

		assertThat(result.events()).isEmpty();
		assertThat(result.page().totalPages()).isZero();
	}

	@Test
	void unknownFieldsAreIgnored() {
		EventsPage result = TicketmasterResponseParser.parse(
				"{\"brandNew\":1,\"_embedded\":{\"events\":[{\"id\":\"a\",\"name\":\"X\",\"futureField\":{\"deep\":[1,2,3]}}]}}");

		assertThat(result.events()).hasSize(1);
	}

	@Test
	void oneEventWithAnUnexpectedShapeDoesNotFailThePage() {
		String broken = "{\"id\":\"bad\",\"dates\":\"not-an-object\",\"images\":{\"also\":\"wrong\"}}";
		EventsPage result = TicketmasterResponseParser.parse(page(0, 1, 3, event("a").json(), broken, event("b").json()));

		assertThat(result.events()).extracting(TicketmasterDto.Event::id).containsExactly("a", "b");
		assertThat(result.unreadableEvents()).isEqualTo(1);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "not json", "<html>Service unavailable</html>", "{\"unterminated\": ", "[1,2,3]", "\"text\"", "42", "null" })
	void bodiesThatAreNotAJsonObjectAreMalformed(String body) {
		assertThatThrownBy(() -> TicketmasterResponseParser.parse(body))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.MALFORMED_RESPONSE);
					// il testo della risposta non finisce mai nel messaggio
					assertThat(e.getMessage()).doesNotContain("html").doesNotContain("unterminated");
					assertThat(e.getCause()).isNull();
				});
	}

	@Test
	void embeddedEventsThatAreNotAListAreMalformed() {
		assertThatThrownBy(() -> TicketmasterResponseParser.parse("{\"_embedded\":{\"events\":\"nope\"}}"))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.MALFORMED_RESPONSE));
	}

}
