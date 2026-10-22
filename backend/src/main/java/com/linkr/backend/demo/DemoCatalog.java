package com.linkr.backend.demo;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linkr.backend.event.EventCategory;

/**
 * Dati della community dimostrativa: 15 personaggi inventati e 36 uscite inventate in luoghi pubblici di Milano.
 * Nessun organizzatore reale, nessun locale privato: i ritrovi sono piazze, parchi e vie. Ogni descrizione dice che e' una demo.
 */
final class DemoCatalog {

	static final String EVENT_DISCLAIMER = " Incontro dimostrativo inventato: non è un evento reale.";

	record DemoEvent(int number, String host, EventCategory category, int dayOffset, LocalTime start, int minutes,
			String title, String description, String venue, String address, String neighbourhood,
			double latitude, double longitude, List<String> tags) {

		UUID id() {
			return UUID.fromString("018f3000-0000-7000-8000-%012d".formatted(number));
		}

	}

	/** Coppie con Link accettato (solo tra account demo). */
	record DemoLink(String a, String b, List<String> messages) {
	}

	static final List<DemoPersona> PERSONAS = List.of(
			new DemoPersona(1, "giulia", "Giulia Ferrante", "Corro lungo i Navigli e poi brunch, sempre.", List.of("running", "cycling", "hiking", "brunch")),
			new DemoPersona(2, "marco", "Marco Bellini", "Techno, dischi e playlist da scambiare.", List.of("techno", "clubbing", "dj-set", "underground-nights")),
			new DemoPersona(3, "sofia", "Sofia Marchetti", "Musei, mostre e passeggiate con la macchina fotografica.", List.of("museums", "contemporary-art", "photography", "design")),
			new DemoPersona(4, "luca", "Luca Moretti", "Calcetto e basket appena posso.", List.of("football", "basketball", "gym", "running")),
			new DemoPersona(5, "elena", "Elena Conti", "Aperitivi, lingue e nuove conoscenze.", List.of("aperitivo", "cocktail-bar", "restaurants", "languages")),
			new DemoPersona(6, "davide", "Davide Greco", "Jazz dal vivo e film da riscoprire.", List.of("jazz", "live-music", "concerts", "cinema")),
			new DemoPersona(7, "chiara", "Chiara Villa", "Allenamento all'aperto e camminate nel verde.", List.of("calisthenics", "hiking", "climbing", "volunteering")),
			new DemoPersona(8, "matteo", "Matteo Russo", "Giochi da tavolo, videogiochi e serate a tema.", List.of("gaming", "technology", "themed-nights", "karaoke")),
			new DemoPersona(9, "alice", "Alice Fontana", "Cinema, teatro e qualche scatto in giro per la città.", List.of("cinema", "theatre", "photography", "brunch")),
			new DemoPersona(10, "tommaso", "Tommaso Riva", "In bici ovunque, con una tappa street food.", List.of("cycling", "running", "street-food", "travel")),
			new DemoPersona(11, "martina", "Martina Galli", "House, garage e festival da pianificare.", List.of("house", "uk-garage", "festivals", "clubbing")),
			new DemoPersona(12, "andrea", "Andrea Lombardi", "Curioso di cucine di ogni parte del mondo.", List.of("restaurants", "tastings", "street-food", "asian-cuisine")),
			new DemoPersona(13, "francesca", "Francesca Serra", "Scambio linguistico e racconti di viaggio.", List.of("languages", "travel", "aperitivo", "museums")),
			new DemoPersona(14, "pietro", "Pietro Colombo", "Hip-hop, basket e un po' di calisthenics.", List.of("hip-hop", "basketball", "calisthenics", "dj-set")),
			new DemoPersona(15, "beatrice", "Beatrice Longo", "Natura, volontariato e fotografia.", List.of("volunteering", "hiking", "photography", "design")));

	private static DemoEvent ev(int n, String host, EventCategory c, int day, int h, int m, int minutes, String title, String desc,
			String venue, String address, String hood, double lat, double lon, String... tags) {
		return new DemoEvent(n, host, c, day, LocalTime.of(h, m), minutes, title, desc, venue, address, hood, lat, lon, List.of(tags));
	}

	static final List<DemoEvent> EVENTS = List.of(
			ev(1, "giulia", EventCategory.OUTDOORS, 1, 7, 30, 75, "Corsa dolce alla Darsena", "Ritmo tranquillo lungo i canali, per chi vuole iniziare bene la giornata.",
					"Darsena", "Darsena, Milano", "Navigli", 45.4535, 9.1745, "running", "cycling"),
			ev(2, "giulia", EventCategory.FOOD, 8, 11, 0, 120, "Brunch dopo la corsa", "Ci troviamo ai giardini e poi scegliamo insieme dove fare brunch.",
					"Giardini Indro Montanelli (ritrovo)", "Giardini Indro Montanelli, Milano", "Porta Venezia", 45.4730, 9.2005, "brunch", "running"),
			ev(3, "giulia", EventCategory.OUTDOORS, 12, 10, 0, 150, "Camminata al Parco Nord", "Un giro lento tra i sentieri, con pausa caffè a metà.",
					"Parco Nord (ritrovo ingresso)", "Parco Nord Milano, Milano", "Niguarda", 45.5300, 9.1890, "hiking", "running"),
			ev(4, "marco", EventCategory.MUSIC, 3, 18, 30, 150, "Playlist swap al parco", "Ognuno porta tre brani preferiti: si ascolta, si commenta, si scambiano consigli.",
					"Parco Sempione (ritrovo)", "Parco Sempione, Milano", "Sempione", 45.4723, 9.1737, "techno", "dj-set", "underground-nights"),
			ev(5, "marco", EventCategory.NIGHTLIFE, 10, 21, 30, 150, "Serata a tema anni '90", "Ritrovo in piazza e poi si decide insieme dove ballare.",
					"Piazza Sempione (ritrovo)", "Piazza Sempione, Milano", "Sempione", 45.4789, 9.1699, "themed-nights", "clubbing"),
			ev(6, "sofia", EventCategory.CULTURE, 2, 17, 30, 100, "Passeggiata tra i murales dell'Ortica", "Un giro a piedi per guardare l'arte di strada del quartiere.",
					"Piazza Ortica (ritrovo)", "Piazza Ortica, Milano", "Ortica", 45.4787, 9.2330, "contemporary-art", "photography"),
			ev(7, "sofia", EventCategory.CULTURE, 6, 17, 45, 120, "Photo walk a Porta Nuova al tramonto", "Scatti con qualsiasi fotocamera, anche quella del telefono.",
					"Piazza Gae Aulenti", "Piazza Gae Aulenti, Milano", "Porta Nuova", 45.4837, 9.1903, "photography", "design"),
			ev(8, "sofia", EventCategory.CULTURE, 13, 15, 0, 120, "Giro intorno al Castello Sforzesco", "Passeggiata tra cortili e giardini, con tappe libere per le foto.",
					"Piazza Castello (ritrovo)", "Piazza Castello, Milano", "Centro", 45.4705, 9.1795, "museums", "contemporary-art"),
			ev(9, "luca", EventCategory.SPORT, 4, 19, 0, 90, "Calcetto amichevole", "Partita tra livelli misti, squadre sorteggiate sul momento.",
					"Parco Lambro", "Parco Lambro, Milano", "Lambrate", 45.5019, 9.2376, "football"),
			ev(10, "luca", EventCategory.SPORT, 11, 18, 30, 90, "Tiri a canestro", "Un'ora e mezza di tiri e partitelle, senza pressione.",
					"Parco Trotter (ritrovo)", "Parco Trotter, Milano", "NoLo", 45.4960, 9.2236, "basketball"),
			ev(11, "elena", EventCategory.SOCIAL, 2, 19, 0, 120, "Aperitivo linguistico", "Si parla italiano, inglese o spagnolo: tavolini misti per ogni livello.",
					"Piazza Vetra (ritrovo)", "Piazza Vetra, Milano", "Ticinese", 45.4590, 9.1810, "languages", "aperitivo"),
			ev(12, "elena", EventCategory.FOOD, 7, 20, 0, 150, "Cena in compagnia", "Ritrovo in via, poi scegliamo il posto in base a chi c'è.",
					"Corso Garibaldi (ritrovo)", "Corso Garibaldi, Milano", "Brera", 45.4800, 9.1850, "restaurants", "aperitivo"),
			ev(13, "elena", EventCategory.NIGHTLIFE, 12, 20, 30, 150, "Giro di cocktail leggero", "Due o tre tappe tranquille, per fare conoscenza.",
					"Piazza Oberdan (ritrovo)", "Piazza Oberdan, Milano", "Porta Venezia", 45.4742, 9.2060, "cocktail-bar", "aperitivo"),
			ev(14, "davide", EventCategory.MUSIC, 5, 20, 0, 120, "Jazz sul Naviglio, ascolto tra amici", "Si ascolta jazz da una cassa portatile e si chiacchiera sulle rive.",
					"Naviglio Grande", "Alzaia Naviglio Grande, Milano", "Navigli", 45.4510, 9.1700, "jazz", "live-music"),
			ev(15, "davide", EventCategory.CULTURE, 9, 19, 30, 120, "Scambio di consigli sui film", "Ognuno porta un titolo da consigliare, si discute sul prato.",
					"Giardini Indro Montanelli", "Giardini Indro Montanelli, Milano", "Porta Venezia", 45.4730, 9.2005, "cinema"),
			ev(16, "davide", EventCategory.MUSIC, 14, 18, 30, 120, "Concerti da scoprire: due chiacchiere", "Si condividono artisti e date che vale la pena seguire.",
					"Piazza Leonardo da Vinci (ritrovo)", "Piazza Leonardo da Vinci, Milano", "Città Studi", 45.4780, 9.2270, "concerts", "live-music"),
			ev(17, "chiara", EventCategory.SPORT, 3, 8, 0, 60, "Allenamento a corpo libero", "Circuito semplice, adatto anche a chi inizia.",
					"Parco Sempione", "Parco Sempione, Milano", "Sempione", 45.4723, 9.1737, "calisthenics", "gym"),
			ev(18, "chiara", EventCategory.OUTDOORS, 9, 9, 30, 120, "Passeggiata al Monte Stella", "Salita facile e vista sulla città, con pausa in cima.",
					"Monte Stella (ritrovo)", "Monte Stella, Milano", "QT8", 45.4917, 9.1350, "hiking", "volunteering"),
			ev(19, "matteo", EventCategory.SOCIAL, 4, 20, 30, 150, "Serata giochi da tavolo", "Ognuno porta un gioco; si decide insieme dove sedersi.",
					"Piazza Morbegno (ritrovo)", "Piazza Morbegno, Milano", "NoLo", 45.5025, 9.2135, "gaming", "themed-nights"),
			ev(20, "matteo", EventCategory.NIGHTLIFE, 13, 21, 0, 120, "Karaoke tra amici", "Ritrovo in piazza, poi si canta dove c'è posto per tutti.",
					"Piazza Duca d'Aosta (ritrovo)", "Piazza Duca d'Aosta, Milano", "Centrale", 45.4853, 9.2043, "karaoke", "themed-nights"),
			ev(21, "alice", EventCategory.CULTURE, 5, 18, 0, 100, "Passeggiata per cinefili", "Si cammina parlando di film e teatro, senza una meta precisa.",
					"Piazza Cinque Giornate", "Piazza Cinque Giornate, Milano", "Porta Vittoria", 45.4600, 9.2060, "cinema", "theatre"),
			ev(22, "alice", EventCategory.FOOD, 10, 11, 30, 120, "Brunch e scatti", "Brunch con la macchina fotografica sempre a portata di mano.",
					"Giardini Indro Montanelli (ritrovo)", "Giardini Indro Montanelli, Milano", "Porta Venezia", 45.4730, 9.2005, "brunch", "photography"),
			ev(23, "tommaso", EventCategory.SPORT, 1, 9, 0, 150, "Giro in bici lungo il Naviglio Pavese", "Pedalata a ritmo facile, con una sosta caffè.",
					"Darsena (ritrovo)", "Darsena, Milano", "Navigli", 45.4535, 9.1745, "cycling"),
			ev(24, "tommaso", EventCategory.FOOD, 6, 13, 0, 120, "Pranzo street food e chiacchiere", "Si gira a piedi e si assaggia quello che ispira.",
					"Piazza XXV Aprile (ritrovo)", "Piazza XXV Aprile, Milano", "Porta Garibaldi", 45.4825, 9.1875, "street-food", "travel"),
			ev(25, "tommaso", EventCategory.SPORT, 11, 19, 0, 60, "Corsa serale al parco", "Un'ora di corsa leggera, per tutti i passi.",
					"Parco Sempione", "Parco Sempione, Milano", "Sempione", 45.4723, 9.1737, "running"),
			ev(26, "martina", EventCategory.MUSIC, 6, 17, 0, 150, "Warm-up house in giardino", "Ascolto condiviso di house e garage, volume rispettoso.",
					"Parco Sempione (ritrovo)", "Parco Sempione, Milano", "Sempione", 45.4723, 9.1737, "house", "uk-garage"),
			ev(27, "martina", EventCategory.NIGHTLIFE, 12, 18, 30, 100, "Aspettando i festival: chiacchiere", "Ci si scambiano idee e date per la prossima stagione.",
					"Piazza Mercanti (ritrovo)", "Piazza Mercanti, Milano", "Centro", 45.4650, 9.1880, "festivals", "clubbing"),
			ev(28, "andrea", EventCategory.FOOD, 3, 19, 30, 120, "Giro di assaggi street food", "Si assaggia a piedi, un piatto a testa da condividere.",
					"Via Paolo Sarpi (ritrovo)", "Via Paolo Sarpi, Milano", "Sarpi", 45.4820, 9.1770, "street-food", "asian-cuisine"),
			ev(29, "andrea", EventCategory.FOOD, 8, 13, 0, 100, "Pranzo asiatico esplorativo", "Si sceglie un posto insieme e si prova qualcosa di nuovo.",
					"Via Paolo Sarpi (ritrovo)", "Via Paolo Sarpi, Milano", "Sarpi", 45.4820, 9.1770, "asian-cuisine", "restaurants"),
			ev(30, "andrea", EventCategory.FOOD, 13, 10, 30, 100, "Assaggi di stagione", "Passeggiata tra banchi e botteghe, senza obbligo di acquisto.",
					"Viale Papiniano (ritrovo)", "Viale Papiniano, Milano", "Sant'Agostino", 45.4570, 9.1700, "tastings", "restaurants"),
			ev(31, "francesca", EventCategory.SOCIAL, 2, 18, 30, 90, "Scambio linguistico al parco", "Coppie di lingue diverse che si alternano ogni quindici minuti.",
					"Parco Sempione", "Parco Sempione, Milano", "Sempione", 45.4723, 9.1737, "languages", "travel"),
			ev(32, "francesca", EventCategory.SOCIAL, 9, 19, 0, 100, "Racconti di viaggio", "Ognuno racconta un posto che ha amato, con una foto o senza.",
					"Piazza Vetra (ritrovo)", "Piazza Vetra, Milano", "Ticinese", 45.4590, 9.1810, "travel", "languages", "aperitivo"),
			ev(33, "pietro", EventCategory.SPORT, 5, 18, 0, 90, "Partitella 3 contro 3", "Partite brevi a squadre miste, per tutti i livelli.",
					"Parco Ravizza (ritrovo)", "Parco Ravizza, Milano", "Porta Romana", 45.4540, 9.1990, "basketball"),
			ev(34, "pietro", EventCategory.MUSIC, 10, 19, 30, 90, "Ascolto hip-hop in compagnia", "Si condividono tracce e si parla di beat.",
					"Arco della Pace (ritrovo)", "Arco della Pace, Milano", "Sempione", 45.4755, 9.1717, "hip-hop", "dj-set"),
			ev(35, "beatrice", EventCategory.OUTDOORS, 7, 10, 0, 120, "Passeggiata con raccolta rifiuti", "Camminata nel verde portando un sacchetto: un gesto semplice, senza organizzazioni coinvolte.",
					"Parco Sempione (ritrovo)", "Parco Sempione, Milano", "Sempione", 45.4723, 9.1737, "volunteering", "hiking"),
			ev(36, "beatrice", EventCategory.CULTURE, 14, 16, 0, 120, "A caccia di vetrine di design", "Una camminata in centro guardando le vetrine e scattando qualche foto.",
					"Piazza del Cannone (ritrovo)", "Piazza del Cannone, Milano", "Sempione", 45.4720, 9.1790, "design", "photography"));

	/** Tema della copertina di ogni evento community (numero evento -> tema di DemoArt). */
	static final Map<Integer, String> COVER_THEME = Map.ofEntries(
			Map.entry(1, "outdoors-run"), Map.entry(2, "food-brunch"), Map.entry(3, "outdoors-hike"), Map.entry(4, "music-dj"),
			Map.entry(5, "nightlife-disco"), Map.entry(6, "culture-gallery"), Map.entry(7, "culture-photo"), Map.entry(8, "culture-gallery"),
			Map.entry(9, "sport-football"), Map.entry(10, "sport-basket"), Map.entry(11, "social-languages"), Map.entry(12, "food-table"),
			Map.entry(13, "nightlife-cocktail"), Map.entry(14, "music-jazz"), Map.entry(15, "culture-cinema"), Map.entry(16, "music-concert"),
			Map.entry(17, "sport-fitness"), Map.entry(18, "outdoors-hike"), Map.entry(19, "social-games"), Map.entry(20, "nightlife-karaoke"),
			Map.entry(21, "culture-cinema"), Map.entry(22, "food-brunch"), Map.entry(23, "sport-bike"), Map.entry(24, "food-streetfood"),
			Map.entry(25, "outdoors-run"), Map.entry(26, "music-dj"), Map.entry(27, "nightlife-disco"), Map.entry(28, "food-streetfood"),
			Map.entry(29, "food-table"), Map.entry(30, "food-table"), Map.entry(31, "social-languages"), Map.entry(32, "social-chat"),
			Map.entry(33, "sport-basket"), Map.entry(34, "music-dj"), Map.entry(35, "outdoors-park"), Map.entry(36, "culture-photo"));

	static final List<DemoLink> LINKS = List.of(
			new DemoLink("giulia", "tommaso", List.of("Ci vediamo alla Darsena per il giro in bici?", "Perfetto, porto io il lucchetto!")),
			new DemoLink("marco", "martina", List.of("Hai già visto la nuova playlist house?", "Sì, bellissima. La ascoltiamo insieme al parco.")),
			new DemoLink("sofia", "alice", List.of("Vieni al photo walk di Porta Nuova?", "Volentieri, porto anche il treppiede.")),
			new DemoLink("luca", "pietro", List.of()),
			new DemoLink("elena", "francesca", List.of("Domani aperitivo linguistico, ci sei?", "Certo, preparo qualche frase in spagnolo!")),
			new DemoLink("davide", "alice", List.of()),
			new DemoLink("andrea", "tommaso", List.of()),
			new DemoLink("chiara", "beatrice", List.of()));

	/** Categoria del catalogo (V3) di ogni interesse usato qui. */
	static final Map<String, String> CATEGORY_OF = Map.ofEntries(
			Map.entry("techno", "music"), Map.entry("house", "music"), Map.entry("uk-garage", "music"), Map.entry("hip-hop", "music"),
			Map.entry("jazz", "music"), Map.entry("concerts", "music"), Map.entry("dj-set", "music"), Map.entry("live-music", "music"),
			Map.entry("football", "sport"), Map.entry("basketball", "sport"), Map.entry("running", "sport"), Map.entry("calisthenics", "sport"),
			Map.entry("gym", "sport"), Map.entry("climbing", "sport"), Map.entry("cycling", "sport"),
			Map.entry("photography", "art"), Map.entry("cinema", "art"), Map.entry("museums", "art"), Map.entry("contemporary-art", "art"),
			Map.entry("theatre", "art"), Map.entry("design", "art"),
			Map.entry("clubbing", "nightlife"), Map.entry("cocktail-bar", "nightlife"), Map.entry("festivals", "nightlife"),
			Map.entry("underground-nights", "nightlife"), Map.entry("karaoke", "nightlife"), Map.entry("themed-nights", "nightlife"),
			Map.entry("street-food", "food"), Map.entry("asian-cuisine", "food"), Map.entry("brunch", "food"), Map.entry("restaurants", "food"),
			Map.entry("aperitivo", "food"), Map.entry("tastings", "food"),
			Map.entry("travel", "lifestyle"), Map.entry("languages", "lifestyle"), Map.entry("gaming", "lifestyle"),
			Map.entry("technology", "lifestyle"), Map.entry("hiking", "lifestyle"), Map.entry("volunteering", "lifestyle"));

	private DemoCatalog() {
	}

}
