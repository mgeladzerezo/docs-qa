Corpus: 4 PDFs, 38 chunks (ingested in 10.7 s). Golden set: 34 answerable, 7 unanswerable questions.

Retrieval on the 34 answerable questions (a chunk is relevant if it contains an expected page)

| method | recall@1 | recall@3 | recall@5 | recall@6 | MRR@10 |
|---|---|---|---|---|---|
| vector only (HNSW, cosine) | 0.853 | 1.000 | 1.000 | 1.000 | 0.926 |
| keyword only (tsvector, ts_rank_cd) | 0.765 | 0.853 | 0.882 | 0.912 | 0.828 |
| hybrid (RRF, k=60) | 0.882 | 0.971 | 1.000 | 1.000 | 0.924 |
| hybrid + MMR (the passages given to the model) | 0.882 | 0.882 | 0.941 | 0.941 | 0.896 |

MRR@10 by question kind

| method | paraphrase (14) | plain (15) | exact (5) |
|---|---|---|---|
| vector only (HNSW, cosine) | 0.929 | 0.933 | 0.900 |
| keyword only (tsvector, ts_rank_cd) | 0.654 | 0.933 | 1.000 |
| hybrid (RRF, k=60) | 0.863 | 0.956 | 1.000 |

Questions where hybrid missed the expected page in its top 5


Abstention (hybrid pipeline, similarity threshold 0.30)

| | questions | abstained | best similarity min / mean / max |
|---|---|---|---|
| unanswerable | 7 | 3 (43%) | 0.12 / 0.39 / 0.70 |
| answerable | 34 | 2 (6%) | 0.26 / 0.51 / 0.79 |

Unanswerable questions that passed the threshold (the model would be asked, and has to say "not found" itself)

- How many calories are in a cappuccino made with the Aurora X2? (best similarity 0.50)
- Does the Lumen cluster support MySQL replication? (best similarity 0.70)
- What is the weather forecast for Port Merrow next week? (best similarity 0.31)
- How much does a bunk at Corvin Hut cost per night? (best similarity 0.63)

Mean search time (embedding the query + SQL): 249.9 ms over 102 searches.
