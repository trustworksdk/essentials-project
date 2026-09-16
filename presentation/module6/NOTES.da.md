# Talernoter — Fra Whiteboard Til Event Store

Modul 6 (*Simplifying with Event Modeling, Event Sourcing and CQRS*) genopbygget omkring en kørende
applikation. 28 slides, 36 minutters indhold, derefter spørgsmål. To appendiks-slides, efter behov.

Hver slide har sin egen note i selve decket — tryk `N` for at vise den på skærmen. Denne fil er
kørselsplanen, begrundelsen for strukturen, og det materiale der blev skåret væk.

Den engelske udgave, `NOTES.md`, er den fulde version. Denne er tættere, og indeholder det samme.

## Betjening af decket

| Tast | Gør |
|---|---|
| `→` `↓` Mellemrum | næste slide |
| `←` `↑` | forrige |
| `Home` / `End` | første / sidste (End stopper på afslutningssliden, ikke i appendiks) |
| `N` | talernote til denne slide |
| `L` | English / Dansk |
| `H` | handout-tilstand — lys palet, til print og lyse lokaler |
| `T` | start / nulstil taler-uret (tæller mod 36:00) |
| `A` | spring til appendiks |
| `?` | tastelisten |

Decket er én selvstændig HTML-fil. Den kræver ingen server og intet netværk, bortset fra de to webfonte
— på en maskine uden forbindelse falder den tilbage til systemfonte og layoutet holder.

## Kørselsplan

| Akt | Slides | Min | Akkumuleret |
|---|---|---|---|
| 0 — kroget | 1–2 | 2 | 2 |
| 1 — fra samtale til model | 3–7 | 7 | 9 |
| 2 — event sourcing, skrivesiden | 8–13 | 9 | 18 |
| 3 — view-projektioner, læsesiden | 14–17 | 5,5 | 23,5 |
| 4 — CQRS, kort | 18–20 | 4 | 27,5 |
| 5 — automatiseringer, integrationer, dual write | 21–24 | 5,5 | 33 |
| 6 — demo, grænser, hvordan man starter | 25–27 | 3 | 36 |

**Er du bagud ved slide 18**, skær slide 19 (kollaborative domæner) og slide 22 (gateway-porten). Begge
er støttemateriale; demoen og grænse-sliden er ikke.

**Er du foran**, brug mere tid på slide 9 (decideren) og slide 16 (de tre svære dele). Det er de to
slides folk spørger om bagefter.

## Akt 0 — kroget (slides 1–2)

Åbn med spørgsmålet, ikke med en definition. *Hvorfor koster dette produkt 1.999,50?* — derefter de fire
opfølgende spørgsmål, derefter rækken til højre, der ikke besvarer nogen af dem.

Bed om håndsoprækning: hvem er blevet spurgt om noget lignende og kunne ikke svare? De fleste lokaler
giver dig halvdelen af hænderne. Forsvar ikke event sourcing endnu; hele oplægget er svaret på det
spørgsmål.

Slide 2 er kortet. Et åndedrag pr. linje, og sig højt at hvert kodepanel er rigtig kode fra en
applikation i dette repository — det ændrer hvordan rummet læser resten.

## Akt 1 — fra samtale til model (slides 3–7)

**Slide 3, event storming.** Workshop-mekanikken ligger i appendiks (`A`); denne slide er kun idéen.
Pointen: ingen gætter eventene, man spørger dem der ved det, og man skriver hvad der *er sket*, i datid.
Den fjerde seddel — `CreditCardHoldRejected` — er den at dvæle ved: nogen i rummet vidste at kort bliver
afvist, og at en afvisning er et forretningsfaktum og ikke en fejl. Netop den indsigt er hvad der holder
det ude af en logfil senere.

**Slide 4, byggeklodserne.** Peg på hver kasse i rækkefølge og navngiv seddelfarven. Trigger, command
(blå), event (orange), view (grøn). Rækkefølgen *er* indholdet: en anmodning, en beslutning, et faktum,
et svar. Derefter den stiplede linje: replay. De samme events genopbygger hvert view og hver beslutning.

Glossen definerer de to ord nybegyndere har brug for — *event-sourced* og *stream*. Læs den hvis rummet
er blandet; spring den hvis alle allerede bygger event-sourcede systemer.

**Slide 5, de tre mønstre.** Sig tallet: tre mønstre, og hver af demoens seksten slices er ét af dem.
Command, view, automation. Automation-mønstret er det folk ikke har mødt — et event lander, det bliver et
stykke arbejde, noget tager arbejdet op — og den vigtige observation er at formen er identisk, uanset om
en maskine eller et menneske lukker sløjfen.

**Slide 6, modellen som mapper.** Den slide der gør metoden konkret. De tre øverste mapper er swimlanes
fra væggen. Seksten slices, og ikke én mappe der heder `services`, `repositories` eller `controllers`. En
ny use case tilføjer en mappe frem for at udvide en eksisterende klasse — det er den praktiske forskel
folk mærker i måned seks.

**Slide 7, test.** Læs Given/When/Then højt fra kasserne, peg derefter på Kotlin-koden og sig: det er
samme sætning. Fremhæv hvad der *mangler* — ingen database, ingen Spring, ingen mocks — og giv tallet:
30 tests, 0,3 sekunder.

## Akt 2 — event sourcing, skrivesiden (slides 8–13)

**Slide 8, navngivning.** Imperativ for en anmodning der stadig kan afvises, datid for et faktum der ikke
kan. Derefter glossen, som er den egentlige lektion: et event skal bære alt hvad dets læsere har brug
for, for en læser kan ikke stille fortiden et spørgsmål. Den konkrete sag er værd at fortælle — da
`ItemRemovedFromShoppingBasket` ikke bar prisen på den fjernede enhed, måtte kurv-viewet og
checkout-totalen hver gætte hvilken enhed der forsvandt, og begge tog fejl når to enheder var tilføjet til
forskellige priser.

**Slide 9, decideren.** Den centrale slide. Gennemgå de tre udfald: et event, intet event, en exception.
Sig derefter hvad der mangler: ingen repository, ingen database, ingen aggregate-klasse. Det er en
funktion fra en kommando og en liste af events til højst ét event.

Hold en pause ved `compareTo`-linjen. `Amount` pakker `BigDecimal`, og `BigDecimal.equals` er
skala-sensitiv — `100.00` er ikke lig `100.0`. Et idempotens-check der sammenligner repræsentationer
frem for værdier tilføjer et ændringsevent der ikke ændrer noget. To linjer der sparer nogen en dag.

**Slide 10, testene.** Tre tests til de tre udfald, plus skala-testen. Den fjerde er der netop fordi det
er en fælde nogen ellers rammer i produktion.

**Slide 11, event store'en.** Peg på de to order-kolonner og sig hvad der er hvad. `event_order` er
positionen inde i én kurv — det er hvad en projektion sammenligner for at være idempotent.
`global_order` er positionen på tværs af alle streams — det er hvad et subscription genoptager fra. Sig
derefter det folk tager fejl af: **tidsstemplet er dokumentation; sortér aldrig efter det.**

Også værd at sige højt: der er intet `UPDATE` og intet `DELETE` nogen steder på sliden. Det er hele
lagringsmodellen.

**Slide 12, evolveren.** Dette besvarer det spørgsmål enhver erfaren udvikler sidder med: hvad hvis
beslutningen kræver tilstand? Man folder streamen. Foldningen lever for én beslutning og smides så væk,
så den kan være præcis det spørgsmål denne slice skal have svar på. Checkout-slicen folder de *samme*
events til en løbende total — to små foldninger frem for én `ShoppingBasketState` der får et felt pr.
slice.

**Slide 13, wiringen.** Kildemodulet springer den over, og her tjener frameworket sit brug. Én bean pr.
aggregate-type, `@Service` på decideren, og én configurator-bean for hele applikationen. Ingen
handler-registrering at glemme, og command bus'en ejer transaktionen, så heller ingen `@Transactional`.

Derefter den ærlige halvdel, som står på sliden som en trade-off: `kotlin-eventsourcing` er markeret
eksperimentel, og en beslutning giver højst **ét** event. Den begrænsning er mest en gave — den tvinger
`CheckOutRequested` frem for `BasketClosed` + `OrderCreated` + `TotalCalculated` — men en beslutning der
reelt kræver to events skal bruge Java'ens `EventStreamDecider`. Sig begge halvdele.

## Akt 3 — view-projektioner, læsesiden (slides 14–17)

**Slide 14, hvorfor projicere.** Læs Greg Youngs linje højt; det er hele argumentet. Derefter den
praktiske version: store'en tilføjer og streamer, og "alle produkter til salg, efter navn" er ingen af de
to ting. Et view er en cache man altid kan genopbygge, og det er hvad der gør det sikkert at have mange.

**Slide 15, projektionen.** Gennemgå handleren, derefter entiteten. Sænk tempoet ved
version-sammenligningen: "sæt prisen til X" anvendt to gange er stadig X, men "læg én til antallet"
anvendt to gange er forkert, så denne kode skal kunne genkende et event den allerede har set.

**Slide 16, de tre svære dele.** Tabellen er argumentet. Rækkefølge og levering er frameworkets opgave —
per-stream rækkefølge, et gemt resume-punkt, en fenced lock så én instans projicerer. Idempotens er
*din*, fordi kun din kode ved hvad det betyder for din tabel at anvende et event to gange. Glossen er
det praktiske råd: skriv projektioner som tildelinger hvor du kan.

Ét forbehold at sige ligeud, fordi to af demoens projektioner afhænger af det: rækkefølge er garanteret
**pr. stream**, ikke på tværs af to aggregate-typer.

**Slide 17, sløjfen.** Følg én prisændring med fingeren, efter tallene. Sig derefter det stille: skrive-
og læsesiden er forbundet af loggen, ikke af et kald. Trin 1–3 er én transaktion; trin 4–5 sker
millisekunder senere på deres egen tidsplan.

## Akt 4 — CQRS, kort (slides 18–20)

Sytten slides fra kildemodulet er komprimeret til tre. Vil nogen have den fulde behandling, er det
originale Modul 6-deck stadig referencen.

**Slide 18, CQS til CQRS.** CQS er idéen på property-niveau som alle allerede bruger: settere ændrer,
gettere svarer. CQRS er samme opdeling et niveau op — "to objekter hvor der før kun var ét", som er Greg
Youngs egen definition. Pointen: et forespørgselsresultat er data, ikke adfærd, så hvorfor sende det
gennem domænelaget? Og med en read model forsvinder diskussionen om eager kontra lazy fetching.

**Slide 19, kollaborative domæner.** Fortæl det som en historie: Anna åbner ordren, Bo åbner samme ordre,
Anna henter kaffe, Bo gemmer, Anna gemmer og får en optimistic locking-fejl. Spørg rummet hvorfor
*brugeren* skal afbrydes af en teknisk begrænsning.

Derefter regnestykket til højre, som er den egentlige pointe: dataene på deres skærm var allerede 120
millisekunder gamle før de rørte dem, plus et par sekunders betænkningstid. **Konsistens var aldrig
øjeblikkelig.**

**Slide 20, handlen.** Begge halvdele, højt. Gevinsten er reel: læsninger konkurrerer ikke længere med
skrivninger, og et nyt spørgsmål koster et view frem for en schema-migrering. Omkostningen er også reel,
og den lander i UI'et — hvilket er bedre end i infrastrukturen hvor den ville være usynlig. Nogen skal
beslutte, sammen med forretningen, hvilke skærme der må halte. Den samtale *er* arbejdet.

## Akt 5 — automatiseringer, integrationer, dual write (slides 21–24)

**Slide 21, automatiseringen.** Den vigtigste sætning: `sales` bad ikke `payment` om dette. Den
registrerede et faktum; `payment` besluttede selv hvad det faktum betyder for den. Slet hele
payment-konteksten og `sales` ændrer sig ikke.

Fortæl derefter historien i glossen, kort, fordi det er det mest nyttige i oplægget for nogen der skal
bygge en. Arbejdsopgave-rækken startede i en *separat* view-slice som policyen læste på sit eget
subscription. Det virkede det meste af tiden — og det er problemet. To subscriptions har ingen rækkefølge
i forhold til hinanden, så policyen kørte igen og igen før rækken fandtes, og lænede sig på genudsendelse.
På en langsommere maskine løb forsøgene ud, beskeden blev en dead letter, og ordren blev stille og roligt
aldrig trukket. At give policyen sin egen tilstand fjernede kapløbet frem for at justere det.

**Slide 22, gatewayen.** Ét sted i hele applikationen laver et synkront kald. Grunden er værd at sige
ligeud: en autorisation er et spørgsmål til tredjepart, og der er intet at registrere før de svarer. Det
holdes ude af decideren så decideren kan replayes — et replay må aldrig trække kortet igen. Og en
afvisning registreres som et faktum, ikke som en fejl i loggen.

**Slide 23, dual write.** Stil fælden op først. To systemer, ingen fælles transaktion, og ingen af de to
rækkefølger er sikre: database-så-broker mister beskeden, broker-så-database annoncerer noget der aldrig
skete, og en distribueret transaktion på tværs af begge er ikke et svar. Lad det stå et øjeblik.

Svaret er næsten antiklimaks: hav kun én skrivning. Decideren tilføjer til event store'en i én lokal
transaktion, og et subscription publicerer bagefter fra den committede stream. Omkostningen står på
sliden — mindst én gang, og et øjeblik senere — og derfor bærer det eksterne event event-rækkefølgen.

**Slide 24, publisheren.** To ting at pege på. Oversættelsen: interne typer bliver almindelige strenge på
vejen ud, i denne ene klasse og intet andet sted. Og `stopRedeliveryOn`: nogle fejl er permanente, og at
gentage en ugyldig besked tyve gange forsinker kun alt bag den.

Afslut akten på driftsforpligtelsen: nogen skal holde øje med dead letter-køen. En dead letter logges,
intet fejler, og forretningsresultatet udebliver bare.

## Akt 6 — demo, grænser, afslutning (slides 25–27)

**Slide 25, demoen.** Skift til browseren og følg `demo-script.md`. Tre beats: køb noget og se resuméet
fyldes ud stykke for stykke; vær lageret og pak ordren; se derefter bagved i admin-konsollen. Hver beat
har en fallback i runbooken — brug den frem for at debugge foran rummet.

**Slide 26, grænserne.** Spring ikke denne slide over, selv med lidt tid. Troværdighed kommer fra
grænserne, og rummet indeholder folk der skal vedligeholde hvad de vælger. Sig sidste linje langsomt: er
eventene ikke fakta forretningen genkender og navngiver, får man maskineriet uden gevinsten.

**Slide 27, afslutningen.** Én konkret handling, ikke et resumé. Modellér det nogen hele tiden skal
forklare — en pris, en status, en saldo, en rettighed. Tegn det på en væg med den der hele tiden spørger.
Derefter én slice. Peg på de to plugin-kommandoer og stier i repositoryet, og hold så op med at tale.

## Appendiks (tryk `A`)

**A1 — at afholde en storming-workshop.** Fire praktiske regler. Brug den hvis nogen spørger hvordan man
faktisk gør. Den første regel er den der betyder noget: uden folk med svarene i rummet skriver man
fiktion.

**A2 — fire ting der bed os undervejs.** `-java-parameters`-flaget, den tavse dead letter,
Testcontainers-livscyklussen, og `BigDecimal`-skala. Godt materiale til "er dette svært?"-spørgsmålet:
ingen af dem er begrebsmæssige, og alle fire står i modulets `CLAUDE.md` så den næste kun betaler én gang.

## Spørgsmål du bør forvente

**"Hvordan er det forskelligt fra en audit-log?"** En audit-log skrives *ved siden af* tilstanden, så de
to kan være uenige, og intet går i stykker når loggen er forkert. Her *er* eventene tilstanden — der er
intet andet at være uenig med.

**"Hvad med GDPR og retten til at blive glemt?"** Reel modsætning, og slide 26 siger det. De gængse svar
er crypto-shredding (eventet gemmer en nøgle, og sletning af nøglen gør payloaden ulæselig) eller at holde
persondata uden for streamen og referere til dem. Begge er designbeslutninger man tager før den første
linje kode.

**"Bliver det ikke langsomt at replaye alt?"** At loade én stream er at loade én lille liste rækker, ikke
hele store'en. Streams der vokser evigt er det egentlige problem, og Essentials har snapshots og closing
books til det — se trading-demoen. Begge er ekstra maskineri, hvilket er en omkostning værd at nævne.

**"Hvordan ændrer vi et events form senere?"** Ved tilføjelse, og forsigtigt: Essentials gemmer det
konkrete klassenavn og tilbyder ingen upcasting, så at omdøbe en event-type gør eksisterende data
ulæselige. Nye valgfrie felter er gratis; omdøbninger er en migrering.

**"Skal vi bruge Kafka?"** Nej. Kafka er kun i demoen for at vise dual write-svaret for events der skal
forlade applikationen. Alt andet — kommandoer, projektioner, automatiseringer — kører på PostgreSQL alene.

**"Hvorfor Kotlin her og Java i den anden demo?"** Fordi `kotlin-eventsourcing` er det modul denne kode
bruger, og det modul de originale Modul 6-snippets blev skrevet mod. Java-ækvivalenten er
`EventStreamDecider` i `eventsourced-aggregates`; trading-demoen viser aggregate-stilen i stedet.

**"Er `kotlin-eventsourcing` produktionsklar?"** Det er markeret work-in-progress, og API'et kan flytte
sig. Sig det ligeud. Mønstrene er ikke eksperimentelle; Kotlin-indpakningen omkring dem er nyere end
Java'ens.

## Tjekliste før oplægget

- [ ] kodepanelerne passer stadig til appen — decket citerer `change_product_price`,
      `remove_item_from_shopping_basket`, `products_for_sale`, `order_summary`, `hold_funds_on_order_placed`,
      `payment_gateway` og `order_management/outgoing`; skim de syv mapper efter enhver refaktorering af demoen
- [ ] `mvn verify -pl :essentials-webshop-demo` grøn, og én gang med `-Pjackson2 … -am`
- [ ] `docker compose -f examples/essentials-webshop-demo/src/main/resources/compose.yml down -v`,
      derefter kør demoen koldt én gang og tag tid
- [ ] decket åbnet offline, i begge sprog, og handout-tilstand tjekket på projektoren
- [ ] uret startet med `T` på titelsliden under det rigtige oplæg
