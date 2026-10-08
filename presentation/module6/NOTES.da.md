# Talernoter — Modul 6, Begreber Og Svar

Begreberne fra *Simplifying with Event Modeling, Event Sourcing and CQRS*, hvert efterfulgt af den
Essentials-kode der implementerer det. 39 slides, 50 minutter, derefter spørgsmål.

Hver slide har sin egen note i decket — tryk `N`. Denne fil er kørselsplanen, hvorfor parrene er parrene,
og hvad der blev udeladt. Den engelske `NOTES.md` er den fulde version; denne er tættere.

## Formen

Femten par, derefter fire slides et spadestik dybere. En **grå** slide siger begrebet med modulets egne ord og dets egne diagrammer hvor de findes;
den **orange** slide bagefter viser Essentials-svaret som rigtig kode fra `examples/essentials-webshop-demo`.
Skinnen nederst viser `n/13`, så både du og rummet ved hvor I er.

Hvorfor den struktur: begreberne kan undervises på et minut hver, og hvad rummet ikke har set er koden.
Timens værdi ligger i den anden slide i hvert par, så brug aldrig mere end cirka ét minut på en grå.

Formatet forklares **ikke** på en slide ud over én linje på kortet. Det forklarer sig selv første gang en
grå slide følges af en orange.

To slides står uden for rytmen. **Slide 2, spørgsmålet** — hvorfor koster denne skærm 1.999,50? — åbner
på et problem en almindelig tabel ikke kan svare på, før noget ordforråd. **Slide 11, kortet over
applikationen**, afslutter åbningshistorien: efter par 2–4 betyder hver kasse noget, og hver orange slide
efter den er et uddrag af netop den webshop.
Diagrammet er samme billede som `examples/essentials-webshop-demo/docs/ui-flow.md` — den fil er Mermaid,
som decket ikke kan rendere, så sliden bærer en håndtegnet SVG af det. **Hold de to i trit** når demoens
slices ændrer sig.

## Betjening

| Tast | Gør |
|---|---|
| `→` `↓` Mellemrum | næste slide |
| `←` `↑` | forrige |
| `Home` / `End` | første / sidste |
| `N` | talernote til denne slide |
| `L` | English / Dansk |
| `H` | handout-tilstand — lys palet, til print og lyse lokaler |
| `T` | start / nulstil taler-uret (tæller mod 48:30) |
| `?` | tastelisten |

Decket kræver ingen server, men det kræver sin `images/`-mappe ved siden af — seks diagrammer hentet fra
modulets egen pptx (se `images/README.md`).

## Kørselsplan

| # | Par | Begrebet, fra modulet | Svaret | Min |
|---|---|---|---|---|
| 1 | Et event er et faktum | slide 2 — ikke-foreskrivende, datid, publisher kender ikke sine subscribers | `sealed interface ProductEvent`, `events/` som eksporteret kontrakt | 2,5 |
| 2–4 | At opdage og modellere → de fire mønstre → slices og capabilities | slides 3–18 — fortalt som én historie, tre grå slides i træk | ét svar for 2–3 (én slice = én mappe, mønster = basistype), ét for 4 (banerne) | 5,75 |
| 5 | Command + tilstand = event | slides 24–25 — formlen, og "aggregates bruges mindre og mindre" | formlen *er* `handle(cmd, events)`; hele decideren | 2,75 |
| 6 | Decideren | slide 26 — mønstret, defineret, med modulets Kotlin | én bean pr. aggregate type, `@Service` på decideren, intet andet | 2,25 |
| 7 | Test kommer fra modellen | slides 14, 20 — Given/When/Then, skrevet før koden | `GivenWhenThenScenario`; 43 tests, 0,3 s, ingen Docker | 2,25 |
| 8 | Event store og replay | slides 27–33 — kurven, animeret over seks slides | `fetchStream` / `appendToStream`, og de to ordninger | 2,5 |
| 9 | Tilstand i en beslutning | slides 68–69 — Evolver-mønstret, modulets egen kode | `Evolver.applyEvents`, én foldning pr. spørgsmål | 2,25 |
| 10 | Hvorfor view-projektioner | slides 39, 61 — Greg Young, og de tre fordele | `ViewEventProcessor` plus en JPA-tabel | 2,25 |
| 11 | Rækkefølge, levering, idempotens | slide 61's tre overvejelser, og den strikse handler på 66 | to er frameworkets, den tredje er din | 2,25 |
| 12 | CQRS og gamle data | slides 42–58 — CQS, CQRS, kollaborative domæner, de 120 ms | forespørgslen rører aldrig domænet, og skærmen poller | 2,5 |
| 13 | Composite UI og automatiseringer | slides 73–74 — én skærm fra mange views, og en to-do-liste | én række fra fire streams; en policy der ejer sin tilstand | 2,75 |
| — | Bonus: dual write | slides 86–88 — problemet, og modulets eget diagram | én lokal transaktion, så publicerer et subscription | 2,5 |
| — | Bonus: et blokerende kald i en handler | ikke i modulet — "registrér, kald så", og hvad *committet* betyder | `UnitOfWorkMode.NONE` på capture-policyen | 2,25 |
| D1 | Dybere: snapshots | — | `@AggregateSnapshotPolicy`, tre tilstande efter hvad et nedbrud koster | 2,5 |
| D2 | Dybere: at lukke bøgerne | — | generationer `acct-1#1` → `acct-1#2`, rollover ved indlæsning | 2,5 |
| D3 | Dybere: change data capture | — | subscriptions får besked fra WAL'en, polling som fallback | 2,5 |
| D4 | Dybere: admin-konsollen | — | to afhængigheder, `/essentials/admin`, to sikkerheds-SPI'er | 2,5 |

Omkring parrene: titlen, **spørgsmålet** (1 min), kortet ("tretten begreber, i fire grupper"
— læs de fire gruppeoverskrifter og intet andet), **kortet over appen** efter par 4 (1,5 min, se nedenfor), og til sidst
"udeladt med vilje" og afslutningen. 4 minutter i alt, 34,5 i parrene, og 10 i de dybere slides. 48:30
indhold efterlader cirka ti minutter af det 60 minutters slot til spørgsmål.

**Er du bagud**, er de dybere slides den elastiske ende: hver står alene, så skær dér først — change data
capture, derefter snapshots. Behold at lukke bøgerne (det besvarer replay-spørgsmålet alle stiller) og
admin-konsollen (den indløser advarslen om dead letters). Først derefter par 11
(rækkefølge/levering/idempotens) og par 12's begrebsslide. Drop ikke par 13 eller dual write — dér gør Essentials mest arbejde for dig.

**Er du foran**, er de to slides der belønner ekstra tid par 5's svar (decideren) og par 13's svar
(automatiseringen, og fejlen i dens gloss).

## Slide 2 — spørgsmålet

Spørg og vent: *hvorfor koster denne skærm 1.999,50?* Rækken til venstre kan ikke svare; de tre events til
højre svarer på hvorfor, siden hvornår, og hvad kunden der bestilte den 20. marts betalte. Nævn ikke event
sourcing endnu — sliden skal bare få rummet til at ville have det næste time forklarer.

## Slide 11 — kortet over appen

Den kommer efter par 4 med vilje: modellen, mønstrene, slices og banerne er lige introduceret, så dette er
belønningen — det hele på én gang — frem for et ledningsdiagram over et system ingen har grund til at
interessere sig for endnu. Læs ikke kasserne op. Fire kolonner og en snes etiketter læser sig selv hurtigere end du kan sige dem.

Følg **én** vej med fingeren i stedet, og sig den som en sætning: *tryk Package i lageret — det er én
kommando; den tilføjer ét event til én stream; en projektion gør den stream til en tabel; et panel viser
tabellen.* Stop så og sig den linje resten af oplægget hviler på: **der er ingen pil tilbage.** Intet i de
venstre kolonner har en reference til en skærm. Derfor kan højre kolonne genopbygges, udskiftes eller
udvides uden at røre venstre, og hvert senere par er en detalje af netop den egenskab.

Derefter de **orange pile**, det eneste på sliden der er værd at pege på to gange. `order_summary` er én
række foldet af fire streams på tværs af alle tre bounded contexts, og lagerets arbejdsliste af tre. Sig at
du vender tilbage til det — det gør du, ved par 13, og så genkender rummet billedet i stedet for at møde
det koldt med halvandet minut tilbage.

To svar du skal have klar:

- *"Hvad er endpointsene?"* — ét `GET` pr. panel, og det er det hele. Bevidst ikke på sliden: en liste af
  URL'er lærer ikke noget som kolonneoverskriften ikke siger, og den inviterer til en REST-diskussion i
  tredje minut.
- *"Hvorfor har Checkout ingen læsemodel?"* — den skriver kun. Den viser det ordre-id browseren dannede og
  intet andet, så ingen projektion peger på den. Det er slidens ene undtagelse og ti sekunder værd, fordi
  den viser at reglen er strukturel frem for en konvention alle fulgte.

Farven er den bounded context, og den er den samme på hver senere slide der har en: sales rav, payment
rød, shipping grøn.

## Parrene, og hvad du siger

**1 — Et event er et faktum.** Begrebssliden er to regler og ingen kode — datid, ikke-foreskrivende — og
den kan læne sig på slide 2. Derefter svarsliden: den sealed familie gør en evolvers
`when` udtømmende, `events/` er én af kun to pakker en anden kontekst må importere, og — den ingen advarer
om — under Jackson 3 er *konstruktør-parameterens navn* JSON-kontrakten, så at omdøbe et felt ødelægger
hvert gemt event.

**2–4 — Én historie, derefter koden.** Tre grå slides i træk, fortalt som én udvikling: storming finder
eventene og modeling sætter dem på en tidslinje (gennemgå modulets legende fra venstre); hver kasse i
modellen er ét af fire mønstre; og en slice af modellen, i en capabilitys bane, er enheden man bygger. Stop
ikke for kode imellem dem.

Derefter to svar. **Fra modellen til koden** (par 2 og 3): én slice er én mappe hvis filer er modellens
kasser — sig fireogtyve — og mønstret du tegnede bestemmer både mappen og basistypen du extender, og typen
bringer præcis det maskineri mønstret har brug for.

**Banerne** (par 4) er et diagram frem for en mappeliste, og tag det i denne rækkefølge: den fyldte blok i hvert
kort (`events/`, `types/` — de eneste to pakker en anden bane må importere), derefter den stiplede blok
(privat, og compileren håndhæver det), derefter **de to røde kryds, som er hele sliden.** Der er ingen pil
mellem kortene. Den eneste vej fra én bane til en anden går ned i store'en og op igen, og derfor ville
`shipping` køre videre hvis `sales` var nede i en time.

Spørger nogen hvordan `shipping` overhovedet kender event-klassen, så tag det — det er slidens bedste
spørgsmål. Der sker to forskellige krydsninger: den *importerer* klassen på compile-tidspunktet, og
*modtager* værdien på kørselstidspunktet fra store'en. Det den aldrig gør, er at **kalde** `sales`.

**5 — Command + tilstand = event.** Modulets formel, derefter metodesignaturen der *er* formlen. Gennemgå
de tre udfald. Sig så hvad der mangler — ingen aggregate-klasse, ingen repository, ingen database, ingen
mocks. Det er hvad "aggregates bruges mindre og mindre" betyder i praksis.

**6 — Decideren.** Modulet definerer mønstret; svarsliden viser wiringen det ikke viser. Én
`AggregateTypeConfiguration` pr. aggregate type, én configurator for hele applikationen, og `@Service` på
decideren. Derefter den ærlige halvdel: `kotlin-eventsourcing` er eksperimentel, og én beslutning giver
højst ét event.

**7 — Test kommer fra modellen.** Læs modulets Given/When/Then, derefter testen, og lad rummet bemærke at
det er samme sætning. Tal: 43 tests, 0,3 sekunder, intet startet.

**8 — Event store og replay.** Modulet animerer kurven over seks slides; begrebssliden komprimerer det til
én tabel med den resulterende kurv i marginen. Peg på de to order-kolonner og navngiv dem præcist. Sig
derefter reglen folk bryder: **tidsstemplet er dokumentation — sortér aldrig efter det.**

**9 — Tilstand i en beslutning.** Dette besvarer det spørgsmål rummet sidder med: uden et aggregate, hvor
bor tilstanden? I en foldning, beregnet inde i beslutningen og smidt væk. Demoens foldning følger priser
pr. enhed frem for antal, og grunden er 20 sekunder værd.

**10 — Hvorfor view-projektioner.** Læs Greg Youngs linje. Derefter svaret: en processor og en tabel. Sig
hvad `ViewEventProcessor` bringer, og at det er sikkert at slette tabellen, fordi replay genopbygger den.

**11 — Rækkefølge, levering, idempotens.** Modulet lister tre overvejelser; svarsliden fordeler dem. To er
frameworkets. Den tredje er din, for kun din kode ved hvad det betyder at anvende et event to gange på din
tabel. Den praktiske regel i glossen fjerner det meste af arbejdet: tildeling er idempotent, inkrementering
er ikke.

**12 — CQRS og gamle data.** Sytten af modulets slides i ét par. Fortæl historien om Anna og Bo, læs
120 ms-regnestykket, og spørg hvorfor brugeren skal afbrydes af en teknisk begrænsning. Svarsliden er en
controller på ni linjer og begge halvdele af handlen.

**13 — Composite UI og automatiseringer.** Modulets farvekodede ordrebekræftelse er den bedste slide i dets
deck; hver kasse er et forskelligt view. Svaret er én projektion over fire streams fra tre kontekster, plus
policyen. Fortæl derefter historien i glossen: arbejdsopgave-rækken boede først i en separat view-slice —
modulets tegning taget bogstaveligt — og den endte som dead letter under belastning.

**Bonus — dual write.** Stil fælden op: to systemer, ingen fælles transaktion, ingen rækkefølge sikker.
Modulets eget håndtegnede diagram navngiver allerede Essentials-komponenterne, så vis det og vis derefter
publisheren. Peg på `stopRedeliveryOn`, og afslut på driftsforpligtelsen: nogen skal holde øje med dead
letter-køen.

**Bonus — et blokerende kald i en handler.** Ikke fra modulet; det er dual writes søskende, og den demoen
faktisk ramte. At pakke trækker kortet, og payment-kontekstens regel er *registrér anmodningen, kald så
gatewayen*. Begrebsslidens fælde: en `@MessageHandler` kører som standard i én transaktion, så anmodningen
skrives først og committes sidst, efter gatewayen har svaret — reglen holder i kildekoden og ikke i databasen,
og en pool-forbindelse står `idle in transaction` under hele kaldet. Svaret er én attribut,
`@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)`, på de to handlere der kan udløse capture; handleren
committer sin egen korte `withUnitOfWork { }` og blokerer derefter uden at holde noget. Nævn de to
forpligtelser: idempotent (decideren returnerer `null` ved genudsendelse, så intet kaldes to gange) og
begrænset (et godt stykke inden køens 30 s timeout). Spørger rummet hvordan vi ved det: `WebshopFlowIT`
registrerer hvad der var sandt i kaldsøjeblikket og fejler hvis en handler sættes tilbage.

**Et spadestik dybere — fire features webshoppen ikke har brug for, eller ikke viser.** Skift i rytmen:
ingen grå begrebsslide, én slide hver, tekst til venstre og ægte kode til højre. Sig fra start at det er
svarene på de spørgsmål folk stiller bagefter.

- *Snapshots.* Tilbage til par 8: replay er billigt indtil streamen er lang. Et snapshot er foldet tilstand
  ved event N, og det er en cache — eventene er stadig sandheden. Nævn tilstandene efter hvad et nedbrud
  koster. Kun aggregate-stilen, så koden er trading-demoens.
- *At lukke bøgerne.* Snapshots gør en lang stream billigere; at lukke bøgerne stopper den i at vokse. Samme
  logiske id, en ny generation per periode (`acct-1#1`, `acct-1#2`); en lukket generation ændrer sig aldrig og
  kan arkiveres. Af de to: overvej denne først.
- *Change data capture.* Alle subscriptions i oplægget poller. Hybrid CDC læser WAL'en og beholder polling som
  fallback — så en fejlkonfiguration koster latens, ikke korrekthed, og ingen opdager det. Derfor
  health-checket. Vær ærlig om at webshoppen ikke slår det til; trading-demoen gør.
- *Admin-konsollen.* Indløser "nogen skal holde øje med dead letter-køen": det er her. To afhængigheder, én
  side, en HTTP-kontrakt med 40 operationer under den. Sig sikkerhedspunktet tydeligt — admin-API'et
  autentificerer ingen selv, og demoens all-access-beans er mærket demo-only af en grund.

## Ingen live demo, med vilje

Femten par, kortet og de dybere slides fylder de 50 minutter, så der er intet demo-segment i decket. Afslutningen fortæller rummet
hvordan de selv kører den, og `demo-script.md` er stadig runbooken hvis du får et længere slot eller rummet
beder om at se det.

Demonstrerer du, så tag det fra par 13: afgiv en ordre på shop-siden og se resuméet fyldes ud felt for felt
mens hvert subscription indhenter. Det er den ene ting en slide ikke kan vise.

## Spørgsmål du bør forvente

**"Hvordan er det forskelligt fra en audit-log?"** En audit-log skrives ved siden af tilstanden, så de to
kan være uenige. Her *er* eventene tilstanden.

**"Hvad med GDPR?"** Reel modsætning. De gængse svar er crypto-shredding eller at holde persondata uden
for streamen. Begge er beslutninger man tager før den første linje kode.

**"Bliver det ikke langsomt at replaye alt?"** At loade én stream er at loade én lille liste rækker.
Streams der vokser evigt er det egentlige problem — det er hvad snapshots og closing books er til, de to første dybere slides.

**"Hvordan ændrer vi et events form senere?"** Ved tilføjelse, og forsigtigt: Essentials gemmer det
konkrete klassenavn og tilbyder ingen upcasting. Nye valgfrie felter er gratis; omdøbninger er en migrering.

**"Skal vi bruge Kafka?"** Nej. Det er kun i demoen for at vise dual write-svaret. Kommandoer,
projektioner og automatiseringer kører på PostgreSQL alene.

**"Hvorfor Kotlin?"** Fordi `kotlin-eventsourcing` er det modul denne kode bruger, og modulets egne
snippets blev skrevet mod det.

**"Er `kotlin-eventsourcing` produktionsklar?"** Det er markeret work-in-progress, og API'et kan flytte
sig. Sig det ligeud.

**"Aggregates bruges mindre og mindre — har vi stadig brug for dem?"** Nogle gange. En decider er den
rigtige standard for en slice-formet use case. Et aggregate tjener sin plads når mange slices deler én
invariant-tung konsistensgrænse — den stil viser trading-demoen.

## Tjekliste før oplægget

- [ ] kodepanelerne passer stadig til appen — decket citerer `change_product_price`,
      `remove_item_from_shopping_basket`, `products_for_sale`, `order_summary`,
      `hold_funds_on_order_placed`, `capture_funds_when_packaged`, `payment_gateway` og
      `order_management/outgoing`
- [ ] `mvn verify -pl :essentials-webshop-demo` grøn
- [ ] decket åbnet offline med `images/` ved siden af, i begge sprog, handout-tilstand tjekket
- [ ] de seks hentede diagrammer passer stadig til pptx'en, hvis modulet selv er blevet redigeret
- [ ] slide 11's kort passer stadig til `examples/essentials-webshop-demo/docs/ui-flow.md` — en slice
      tilføjet eller flyttet i demoen ændrer begge, og deckets kopi er håndtegnet SVG som intet genererer
- [ ] uret startet med `T` på titelsliden
