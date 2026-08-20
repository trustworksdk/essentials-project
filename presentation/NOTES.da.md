# Talernoter — The Event Is The Record

Dansk. Den engelske version er `NOTES.md`. Selve decket har også en kort note pr. slide, som vises med
`N` — denne fil er den fulde begrundelse bag hver af dem.

Tekniske termer, klassenavne, endpoints og kodeidentifikatorer står på engelsk, fordi det er sådan de står
i koden på skærmen.

## Styring af decket

| Tast | Gør |
|---|---|
| `→` `↓` `Space` | næste slide |
| `←` `↑` | forrige slide |
| `Home` / `End` | første / sidste |
| `N` | talernote til den aktuelle slide |
| `L` | English / Dansk |
| `H` | handout-tilstand (lys) — til print eller et lyst lokale |
| `T` | start / nulstil taler-uret, vist i bundlinjen mod 36:00 |
| `A` | spring direkte til appendiks — de fire forkerte svar på krav nummer tre |
| `?` | tastoversigt |

**Hvor appendikset er.** Én slide, der ligger efter afslutningssliden og **ikke** er en del af det
tidsbudgetterede forløb. Tryk `A`, eller klik `APPX` i øverste højre hjørne. Bundlinjen mærker den `A1 / A1`
i stedet for at fortsætte slide-tællingen, og fremdriftsbjælken bliver fuld, så et smut derind midt i Q&A
ikke ødelægger din fornemmelse af hvor du er. `←` går tilbage ind i decket. `End` går bevidst til
afslutningssliden, ikke forbi den.

Decket er bevidst mørkt uanset temaet på den maskine det vises på, så det ikke kan skifte udseende midt i
oplægget fordi en bærbar står på lyst tema. `H` er den eneste vej til den lyse palet.

URL'en indeholder slide-nummeret (`#s14`), så du kan sende nogen et link til en enkelt slide.

## Kørselsplan

30 slides i forløbet plus én appendiks-slide, 36 minutters indhold. Bundlinjen viser hver slides
minutbudget, og budgetterne summer til netop det tal — så siger bundlinjen at du er på en 2-minutters slide,
så mener den det.

| Slides | Akt | Minutter | Kan skæres |
|---|---|---|---|
| 1–3 | Titel, hook, dagsorden | 1,5 | nej |
| 4–10 | Akt 1 — tre krav, tre steder de lander | 11 | nej — det er den akt publikum har mest brug for |
| 11–12 | Akt 2 — ét aggregate, tæt på | 4,5 | nej |
| 13 | Akt 3 — skilleslide | 0 | ja, hvis du allerede er bagud |
| 14–24 | Akt 3 — elleve komponenter | 10,5 | slide 19 (snapshots), 20 (closing books), 22 (inbox/outbox) |
| 25–27 | Akt 4 — live demo, to segmenter plus konsollen | 4,5 | Demo C (konsollen), derefter Demo B |
| 28 | Akt 5 — hvor det ikke passer | 2,5 | nej — den gør resten troværdigt |
| 29–30 | Akt 6 — AI-segment, hvordan man starter | 1,5 | AI-sliden, hvis demoen trak ud |
| A1 | Appendiks — fire forkerte svar på krav nummer tre | 0 | kun på forespørgsel |
| — | Spørgsmål | 8–10 | — |

**Akt 1 er oplæggets rygrad.** Syv slides, 11 minutter, og bevidst den største akt i decket: tre krav ankommer
i stigende sværhedsgrad, og hvert af dem lander et andet sted.

| Krav | Hvor reglen lander | Slides |
|---|---|---|
| Registrér et instrument | inde i aggregatet — én consistency boundary | 4–6 |
| To instrumenter må ikke dele symbol | i handleren, mod en read model — et værn, ikke en garanti | 7–8 |
| En ekstern service skal godkende | i en automation, efter transaktionen er committet | 9 |

Det er hele slice-taksonomien — command slice, view slice, automation slice — undervist gennem krav frem for
gennem et diagram over frameworket. Får du kun én akt rigtigt, så få denne rigtigt.

**For at ramme et 30-minutters slot** skær slide 19, 20 og 22 (3 minutter) og Demo C (1 minut), og hold
Akt 1 og Akt 2 hele. Skær ikke slide 8 (unikheds-race'et) eller slide 9 (det eksterne kald) for at spare tid:
slide 8 uden sit race lærer dem noget forkert, og slide 9 er det spørgsmål dine kolleger faktisk står med
dette kvartal.

**Er du ved slide 24 og der er gået mere end 29 minutter**, så spring admin-konsol-segmentet over og gå
direkte fra Demo B til Akt 5. Skær aldrig Akt 5 for at redde demoen.

## Akt 0 — hooket (slide 2–3)

Bed om håndsoprakning: hvem er blevet spurgt hvorfor en række i en database ser ud som den gør, og kunne
ikke svare? Det spørgsmål er hele oplægget. Forsvar ikke event sourcing endnu — du har ikke fortjent det.

De fire spørgsmål på slide 2 vender tilbage på slide 12 som kontrasten, og igen i demoen når den rå event
stream besvarer dem. Brug de samme fire, i samme rækkefølge, alle tre gange.

Slide 3 er et kort, ikke indhold. Ét åndedrag pr. linje.

## Akt 1 — modellering (slide 4–10)

**Slide 4, event-modellen.** Læs kravet højt i forretningsord først: *market data-teamet registrerer
instrumenter.* Peg derefter på hver kasse og navngiv hvad den er — kommandoen er en anmodning, aggregatet
er beslutningen, eventet er faktum, viewet er svaret. Den stiplede returpil er replay: de samme events der
blev skrevet genopbygger aggregatet. Ingen har åbnet en IDE endnu, og sig det.

**Slide 5, `slice.yaml`.** Denne fil er broen fra whiteboard til repository. Peg på `handles`, `publishes`,
`writes` — det er whiteboardet, sat på skrift. Derefter invarianterne. INV-RI-2 er en ægte designbeslutning
værd at dvæle ved: det er *kalderen* der leverer `InstrumentId`, så en gentaget registrering rammer det
samme instrument i stedet for at oprette et nyt. Det er idempotens besluttet i kontrakten, ikke lappet på
bagefter. `forbidden` er en reel constraint, som både en reviewer og en coding agent kan tjekke.

**Slide 6, slicen på disken.** Fire filer, én mappe. Sig tallene: 19 command-slices og 6 view-slices i
`brokerage`, 5 og 2 i `market_data`, og ikke én af dem er et service-lag. Command-recorden *er* request
body, så der er ingen DTO og ingen mapper at holde i trit. De typede `InstrumentId` og `Symbol` kommer
retur igen fordi web-`ObjectMapper`en har Essentials' Jackson-modul registreret — én linje konfiguration,
ikke annotationer pr. felt.

**Slide 7 og 8, unikheds-kravet.** Et nyt krav ankommer: *to instrumenter må ikke handle under samme
symbol.* Disse to slides findes fordi det er spørgsmålet rummet faktisk vil stille, og fordi det er her
metoden viser sit værd.

Åbn med at sige at denne **ikke er i repositoriet endnu** — det er det næste krav, ikke en rundtur i
eksisterende kode. Sig det én gang, tydeligt, så ingen leder efter `SymbolAlreadyRegistered` bagefter og
konkluderer at slidene var opdigtede.

Derefter slide 7, i tre slag:

1. **Kontrakten vokser før koden gør.** `INV-RI-3` får et id som de to andre, og `reads:` holder op med at
   være tom. Alle kan nu se, ud fra YAML'en alene, at denne command-slice afhænger af et view.
2. **Hvorfor aggregatet ikke kan håndhæve den.** Det er aktens bærende sætning: et aggregate er en
   consistency boundary, så det ser sin egen stream og intet andet. En regel om hele *mængden* af
   instrumenter kan ikke bo inde i ét af dem. At tjekke den dér ville betyde at loade hvert instrument i én
   transaktion, hvilket ikke er hvad en boundary er til.
3. **Så flytter beslutningen op.** Handleren spørger `instrument_details`-viewet — den samme read model som
   Akt 3's projektions-slide, mødt tidligt. Peg på `filter`: den bevarer INV-RI-2, så *samme* registrering
   gentaget stadig lykkes lydløst, og kun et *andet* instrument der gør krav på symbolet afvises. Giv dem
   fagudtrykket, **set-based validation**, så de kan læse om det bagefter.

Derefter slide 8, og spring den ikke over. Viser du checket uden race'et, har du lært dem noget forkert, og
den første senior i rummet finder hullet på ti sekunder. Gå de fire tidspunkter roligt igennem: to
instanser, to transaktioner, projektionen er ikke fulgt med, begge passerer, begge skriver.

Sig tydeligt at det ikke kan rettes ved at prøve hårdere — at læse to gange, eller læse inde i
transaktionen, hjælper ikke, fordi den række den skulle have ikke findes endnu.

Derefter de tre udveje, og forpligt dig til én: for et ticker-symbol, **detect og repair**, fordi et dobbelt
ticker er pinligt snarere end farligt. Var det penge, tag mulighed to og gør den unikke værdi til et
aggregate-id. Mulighed tre, en `FencedLock` omkring registrering, er det billige pragmatiske svar og den
peger frem mod komponent 08 — værd at nævne, så Akt 3 lander som et tilbagekald i stedet for nyt stof.

Sætningen der skal lande: **vælg pr. regel, ikke pr. system.**

En konsekvens værd at nævne hvis nogen spørger: at lade handleren læse et view gør det view-slices query til
en del af dens offentlige flade. Det er en reel koblings-beslutning, ikke en gratis frokost.

**Slide 9, den eksterne godkendelse.** Krav nummer tre: *en ekstern risk-service skal godkende instrumentet
før det kan handles.* Samme forbehold som de to foregående slides — proposed, ikke i repositoriet. Sig det én
gang.

Det er den slide dine kolleger vil genkende fra deres egen backlog, og instinktet i rummet vil være rigtigt:
et blokerende HTTP-kald hører ikke inde i en databasetransaktion. Bekræft det, og korrigér derefter
*begrundelsen*, for de fleste giver den svage.

Den svage grund er ressourcebinding — en connection og aggregatets låse bundet under hele netværks-rundturen,
så en risk-service der degraderer til fem sekunders latency bliver et connection-pool-udfald i din service.
Sandt, og det kan tunes.

Den grund der faktisk afgør sagen: **et rollback kan ikke af-kalde en ekstern service.** Når requestet først
er sendt, er det sket. Fejler transaktionen derefter og køres igen, kaldes risk-servicen en anden gang. Der
findes ingen korrekt version af ”inde i transaktionen”, så transaktionen skal slutte før kaldet.

Derefter de fire blokke, i rækkefølge:

1. **tx 1** — `InstrumentRegistrationRequested`, status `PENDING_RISK_APPROVAL`, og samme transaktion køer
   `RiskCheckDeadlineReached` med 15 minutters leveringsforsinkelse. Endpointet svarer `202`, ikke `201`:
   instrumentet findes, men er ikke handelbart.
2. **køen** — `InstrumentRiskCheck extends EventProcessor` tager eventet af sin `Inbox` uden nogen
   `UnitOfWork` åben og laver et almindeligt HTTP-kald. Peg på idempotency key'en: at-least-once betyder at
   risk-servicen *vil* nu og da se samme check to gange.
3. **tx 2** — svaret kommer tilbage som en kommando, `RecordRiskDecision`, og aggregatets tilstandsmaskine
   fuldføres: `ACTIVE` eller `REJECTED`.
4. **tx 2'** — ingen svarer. Den forsinkede kommando lander, tjekker om instrumentet *stadig* er pending, og
   timer det ud. Det tilstandstjek er hvad der gør den forsinkede kommando sikker at levere mere end én gang.

Værd at sige rent ud: **`EventProcessor` er bygget til dette.** Framework-docs angiver dens rolle som eksterne
integrationer og langvarige operationer. Inbox-baseret, eksklusiv via en `FencedLock`, gentages efter en
policy du sætter, dead-letter'er når afhængigheden bliver nede, og den giver dig `getCommandBus()` — så hele
automatiseringen er én klasse med to handlere. Det er også her `FencedLock` holder op med at være et
punktopstillingspunkt og laver rigtigt arbejde.

Land på domænesætningen: **tager en beslutning tid, er ventetiden en del af domænet.** Modellér ventetiden;
skjul den ikke i en thread pool.

Presser nogen — ”hvorfor ikke bare kalde den og være færdig” — tryk `A` for appendikset.

**Slide 10, bounded contexts.** Her ligger DDD-udbyttet, og det er sliden seniorerne vil presse på.
`brokerage` skal have en pris for at værdisætte en trade. Den kalder *ikke* `market_data`. Den subscriber
på to events og bygger sin egen read model. Sig alternativet ligeud: at injecte det andet konteksts
service — som er præcis det denne løsning erstattede i demoens egen historie. Stream-navnene bor i
`types/` fordi et fremmed kontekst skal kunne navngive den stream det subscriber på, og `aggregates/` må
ikke importeres.

## Akt 2 — aggregatet (slide 11–12)

**Slide 11** er den vigtigste slide i decket. Sæt tempoet ned. Fire slag, i denne rækkefølge:

1. Metoder kalder `apply`. De assigner aldrig tilstand.
2. `@EventHandler`-metoderne er det eneste sted de fire felter nogensinde skrives.
3. De samme handlers kører ved rehydrering, så replay af historik og håndtering af en ny kommando er én
   kodesti — og derfor kan adfærd og historik ikke glide fra hinanden.
4. De to tidlige returns er idempotens. At omdøbe til det navn der allerede holdes, eller suspendere et
   allerede suspenderet instrument, applyer ingenting. En gentaget kommando efterlader intet spor i stedet
   for at forlænge streamen.

Derefter rammen der gør at det bliver hængende: `Instrument` er demoens bevidste baseline. Ingen snapshots,
ingen closing books, korte streams af konstruktion — et instrument registreres én gang, omdøbes lejlighedsvis
og suspenderes højst én gang. Alt i Akt 3 måles mod denne klasse.

**Slide 12, kontrasten.** Hån ikke JPA-entiteten. Den er kortere, hurtigere at skrive, og det meste af vores
kode ser sådan ud. Hold påstanden snæver: den kan ikke besvare de fire spørgsmål fra åbningen, og ekstra
log-linjer retter det ikke, fordi logs ikke er kilden til sandhed og ingen replayer dem.

## Akt 3 — komponenterne (slide 13–24)

Hver slide ender i et trade-off. Sig omkostningen med samme energi som gevinsten — et framework-oplæg uden
omkostninger er et salgspitch, og det kan rummet lugte.

**Komponent 01 — `types` (slide 14).** To klasser, tre linjer hver. Pointen er hvad de gør umuligt. Bemærk at `Symbol`
serialiseres som den samme rå JSON-streng den erstattede, så indførelsen ændrede intet persisteret payload
— det er sådan man indfører semantiske typer i et eksisterende system uden en migrering.

**Komponent 02 — `UnitOfWork` (slide 15).** Tre forskellige slags skrivninger, én commit. Derefter demo-faktum: ikke én
`@CmdHandler` i kodebasen bærer `@Transactional`, fordi command-bussen åbner unit of work. Den kommentar
står i den rigtige handler, ikke tilføjet til sliden.

**Komponent 03 — event store (slide 16).** Den ene ting de skal gå derfra med: to ordninger, og ingen af dem er et timestamp.
`EventOrder` er position inden for én stream; `GlobalEventOrder` er position på tværs af alle streams for
en `AggregateType`, og det er det en durable subscription genoptager fra. Sig rent ud at ure aldrig bruges
til ordning — det er fejlen alle tager med fra en CRUD-baggrund.

**Komponent 04 — aggregate eller decider (slide 17).** To baner, begge supporterede. Venstre er hvad demoen bruger; højre er den
funktionelle, testet uden database og uden Spring-kontekst. Vær derefter eksplicit: demoen ligger helt på
aggregate-banen med vilje, og vi konverterer den ikke. En kodebase der kører begge baner til samme slags
problem er værre end begge baner valgt konsekvent.

**Komponent 05 — projektioner (slide 18).** Den mest praktisk nyttige slide i akten. Standarden er venstre: projicér eventene,
servér læsninger fra din egen tabel, acceptér forsinkelse. Højre er den dokumenterede undtagelse, og læs
begrundelsen fra koden — bootstrap-proben spørger om en pris allerede findes, og et eventually consistent
"fraværende" ville lade den seede en anden gang oven i levende data. Én undtagelse, skrevet ned, med en
navngiven grund. `onSubscriptionsReset` er ti sekunder værd: den gør genopbygning af en read model til en
knap i stedet for en migrering.

**Komponent 06 — snapshots (slide 19).** *Ét minut, og en af de første der skæres.* Bevidst holdt overfladisk: konceptet er hele
pointen. At loade et aggregate replayer dets events, så en lang stream koster mere at loade, og et snapshot
er en gemt sammenlægning af streamen op til event *n*. Én annotation tænder det. Sig hvorfor `Instrument`
ikke deklarerer nogen — en kort stream er billigere at replaye end et snapshot er at vedligeholde — og gå
videre. Gå ikke ind i tærskel-tuning; ingen har brug for `everyNEvents`-regnestykker i et første oplæg.

**Komponent 07 — closing the books (slide 20).** *Ét minut, også en kandidat til at blive skåret.* Dette besvarer den indvending
du vil få: at streams vokser i det uendelige. Det gør de ikke. Regnskabs-analogien gør arbejdet — intet
omskrives og intet slettes, den gamle stream lukkes med en slutsaldo og en ny åbner med den som
udgangspunkt. Derefter den ærlige omkostning: to id'er pr. konto, som er reel ekstra kompleksitet man kun
skal påtage sig når streams faktisk vokser uden grænse. Stop der.

Mellem de to slides har du to minutter i alt. Fanger du dig selv i at forklare parsing af generation-id'er,
har du overskredet begge.

**Komponent 08 — køer og låse (slide 21).** Dvæl ved `getCurrentToken()`, for det er forskellen fra en almindelig mutex. Tokenet
er monotont, så en holder der gik i stå, mistede låsen og vågnede med det gamle token kan afvises af det
den skriver til. En mutex kan ikke udtrykke det. Derefter den hårde begrænsning, to gange hvis nødvendigt:
dette koordinerer instanser af *én* service mod *én* database. Ikke på tværs af services.

**Komponent 09 — inbox/outbox (slide 22).** *Skæres som nummer to hvis du er bagud.* Nævn dual-write-problemet i én sætning: gem
i databasen, publicér til Kafka, og processen dør mellem de to. Vær derefter ligeud om at dette giver
at-least-once, ikke exactly-once, så hver downstream-consumer skal være idempotent — og det er nu også
deres designbegrænsning, hvilket er en samtale du skylder dem inden I går i produktion.

**Komponent 10 — starters (slide 23).** De fulde artifact-navne står på sliden, så læs ét op og lad tabellen klare resten.
Budskabet er at det er almindelige Spring Boot-startere: én dependency, en håndfuld properties, og bean'ene
findes. Der er ingen Essentials-bootstrapklasse og ingen XML. Det stærkeste konkrete eksempel er at
`@Service extends AnnotatedCommandHandler` er den *komplette* registrering af en command handler.

Derefter deklarations-bean'en: én pr. bounded context, flettet på tværs af kontekster, som parrer en
`AggregateType` — stream-navnet — med den klasse hvis events havner i den. Den erstattede to håndskrevne
`InitializingBean`s, hvilket er en sætning værd fordi det viser hvilken retning ergonomien har bevæget sig.

Der er bevidst ingen trade-off-boks på denne slide.

**Komponent 11 — admin-konsollen (slide 24).** Hold den kort; den er en bro til demoen. Konsollen er ikke noget vi vedligeholder
— den kommer med starteren.

## Akt 4 — demoen (slide 25–27)

Skift vindue ved slide 25. Se `demo-script.md` for de præcise kommandoer og reset-proceduren.

Appen skal være startet og varm inden rummet fyldes. Byg ingenting på scenen.

To slides og tre segmenter, 4,5 minutter. Snapshot- og generations-demoen er bevidst droppet: snapshots og
closing books har allerede hver sin slide i Akt 3, og at demonstrere dem oveni gjorde to sekundære
mekanismer til oplæggets centrum.

**Demo A — en slice, tur-retur (slide 26).** Kør de første fire kald hurtigt, de er opsætning. Stop på det
sidste og lad rummet læse det. Peg på at omdøbningen er et andet event, ikke en overskrivning. Hvis det
første `GET` halter et øjeblik, er det slide 18 der sker i rummet, ikke en fejl — sig det før nogen begynder
at spekulere.

**Demo B — omkostningen, målt (slide 27).** Her fortjener du rummet. Kør sammenligningen, og sig så
forbeholdet før nogen anden kan: aggregate-stien måles lidt tungere end nødvendigt, fordi afsendelse på
command-bussen lægger dispatch, handler-opslag og `UnitOfWork`-interceptoren inden for det målte vindue,
mens direct-write-stien er uændret. Sammenligningen er skæv imod event sourcing, og vi viser den alligevel.
Derefter reglen: event-source det du skal kunne forklare; event-source ikke et tick-feed bare fordi du kan.

**Demo C — admin-konsollen (ingen slide).** Ét minut, kørt direkte fra browseren i forlængelse af slide 24.
Aggregate lookup, derefter køer og låse. **Skæres først** hvis du er bagud.

Driller et segment, så åbn optagelsen fra `recordings/` og fortsæt med at tale. Debug ikke på scenen.

## Akt 5 — begrænsningerne (slide 28)

Hast ikke gennem denne, og undskyld dig ikke igennem den. Denne slide er hvorfor resten af oplægget bør
tros.

Har du kun tid til to linjer, tag **kun intra-service** og **et events persisterede type er dets
klassenavn**. Det er de to der faktisk bider i produktion. Den anden har en krigshistorie med: denne demos
egen README beder dig slette databasen, fordi slice-refaktoreringen omdøbte hver event-klasse og der ikke
blev leveret upcasting. JSON-payloadene var uændrede — det var udelukkende en type-omdøbning — og det
gjorde alligevel hvert eksisterende event ulæseligt.

## Akt 6 — afslutning (slide 29–30)

**Slide 29, AI-segmentet.** Vis diffen, afspil optagelsen, og fremsæt kun den snævre påstand: strukturen er
det der lod agenten lægge ændringen på rette sted, fordi slice-mappen, dens `CLAUDE.md` og dens
`slice.yaml` siger hvad der hører til der. Påstå ikke at agenten forstod domænet.

**Slide 30.** Land på den ene sætning — event-source det du skal kunne forklare, projicér alt du skal kunne
query'e, hold resten kedeligt — hold så op med at tale og tag spørgsmål.

Er rummet stille, så stil selv spørgsmålet: hvad i vores systemer har en audit-samtale hængende ved sig?
Det er kandidaten. Ikke hele platformen.

## Appendiks — fire forkerte svar på krav nummer tre (slide A1, tryk `A`)

Selve mekanismen er nu slide 9, i forløbet. Denne appendiks-slide er den du griber efter når nogen presser
tilbage, eller spørger *hvorfor ikke bare gøre X* — og X er altid ét af disse fire. Mindst én i rummet har
sendt hvert af dem i produktion.

**HTTP inde i `@CmdHandler`.** Den naive version. Det er inde i `UnitOfWork`'en, så connection og
aggregate-låse holdes under rundturen, og rollback-problemet fra slide 9 gælder fuldt ud: en genkørt
transaktion kalder servicen to gange.

**`afterCommit` / `TransactionSynchronization`.** Det subtile, og det der er værd at dvæle ved, fordi det ser
rigtigt ud — det *er* på den rigtige side af commit. Men det er ikke durable. Processen dør mellem commit og
callback, og kaldet er væk, uden at noget nogen steder har registreret at det skyldtes. Det er præcis det hul
en inbox eller en outbox findes for at lukke, hvilket gør dette til et tilbagekald til komponent 09 frem for
nyt stof. Skal du kun gøre én pointe ud fra denne slide, så gør denne.

**In-memory `EventBus`-listener.** Samme tab, og den dør med JVM'en. Fin til cache-invalidering, ikke til en
forpligtelse over for tredjepart.

**”Gør den `@Async`.”** Forpligtelsen bor nu i en thread pool i RAM. En genstart glemmer den, der er ingen
redelivery, ingen dead letter queue, og intet at se på når nogen spørger hvorfor et instrument har været
pending i to dage.

**Derefter spørgsmålet der skal tilbage til forretningen.** Betyder ”godkendt først” før instrumentet er
*handelbart*, eller før HTTP-svaret returnerer? Det er næsten altid det første, og det er forskellen mellem at
dette er en korrekt model og at det er en omgåelse. Har de reelt brug for et synkront ja/nej i svaret, bygger
du en synkron facade over en asynkron proces — sig det højt og prissæt det.

**Indvender nogen** at `Instrument` bærer en tilstand der kun findes på grund af en ekstern afhængighed — en
fair indvending — er svaret at give processen sit eget aggregate, en `RiskCheck`-stream, og holde instrumentet
rent. Det er process-manager-varianten, og det er det rigtige valg når godkendelsesflowet vokser med flere
trin.

**Webhook-varianten.** Kalder risk-servicen dig tilbage i stedet for at svare i responsen, skriver
webhook-endpointet til en **Inbox**: gemt og de-dupliceret først, håndteret i sin egen transaktion bagefter.
Samme princip, spejlet, og det koster ikke ekstra fordi inboxen allerede er der.

**Generalisér det inden du sætter dig.** Samme form dækker hvert udgående kald — betalingsautorisation,
KYC-opslag, kreditbeslutning, en e-mail, en PDF der skal genereres. Alt hvor svaret ikke er dit at beregne, og
arbejdet ikke må gå tabt.

## Spørgsmål du bør forvente

| Spørgsmål | Kort svar |
|---|---|
| "Er det ikke bare Axon / EventStoreDB?" | Andet scope. Dette er byggeklodser på den PostgreSQL I allerede driver, uden broker og uden separat cluster. Intra-service af design, og det siger det selv. |
| "Hvad med GDPR-sletning?" | Det ærlige svar er at det er reelt arbejde: crypto-shredding eller en omskrivning af de berørte streams. Tal dig ikke fra det. |
| "Hvordan migrerer vi en eksisterende tabel?" | Det gør man ikke i ét hug. Vælg ét aggregate hvis historik folk bliver ved at spørge om, og kør det sideløbende. |
| "Hvad koster replay på en stor stream?" | Det er præcis hvad snapshots og closing books er til — og demoen leverer et benchmark for begge, som I lige har set. |
| "To Jackson-versioner lyder skrøbeligt." | Kun én er på classpath ad gangen; de deler klassenavne og skriver byte-identisk JSON. Buildet kører begge profiler. |
| "Kan vi event-source alt?" | Nej, og Demo B er grunden. Event-source beslutninger, ikke tick-feeds. |
| "Hvordan laver man så en unique constraint?" | Slide 7–8 besvarer det, så du kan sende dem tilbage dertil. Kort version: ikke inde i ét aggregate. Enten accepterer man vinduet og detekterer kollisioner, eller man gør den unikke værdi til et aggregate-id. |
| "Kunne man ikke bare lægge et unique index på projektionstabellen?" | Det flytter fejlen, det forhindrer den ikke. Eventet er allerede skrevet og committet på det tidspunkt, så projektoren fejler i stedet for kommandoen — man har byttet en afvist registrering for en fastlåst projektion. Fint som *detektor*, ikke som håndhævelse. |
| "Vi skal kalde en ekstern service, og den skal godkende først." | Tryk `A`. Kort version: ikke inde i transaktionen, fordi et rollback ikke kan af-kalde den. En `EventProcessor` laver kaldet efter commit, svaret kommer tilbage som en kommando, og deadline er en delayed command. |
| "Betyder at-least-once ikke at vi kan kalde risk-servicen to gange?" | Jo, og det er ikke skjult — det er derfor kaldet bærer en idempotency key. Ethvert eksternt kald fra en durable consumer skal have en; det er den normale pris for ikke at miste kaldet i stedet. |

## Tjekliste før generalprøven

- [ ] `presentation/snippets/extract.sh` kørt igen, ingen `MISSING SOURCE`
- [ ] Fuld gennemkørsel mod et ur, mindst én gang, højt
- [ ] Demo kørt fra start til slut fra en slettet database
- [ ] Alle fire demo-segmenter optaget til `recordings/`
- [ ] Decket åbnet på den maskine der skal præsentere, i den rigtige opløsning
- [ ] `H` handout-tilstand tjekket, i tilfælde af et lyst lokale
- [ ] `L` tjekket på to-tre slides på dansk, så et skift midt i oplægget ikke overrasker
- [ ] Uret (`T`) startet ved slide 1 under generalprøven, for at finde det reelle overtræk
- [ ] Decket tjekket for mojibake — hvert `æ`, `ø`, `å` i den danske tekst vises som sig selv. Filen
      deklarerer `<meta charset="utf-8">`; forsvinder den linje, går hver dansk slide i stykker på én gang
