# Golden generation report

`legacy` = docx_store._buildPlainMap (reference). `clean` = native engine target.

| fixture | prose? | clean==legacy? | len(legacy) | len(clean) | clean P |
|---|---|---|---|---|---|
| empty_para | yes | ✅ | 13 | 13 | `above\n\nbelow\n` |
| entities | no | ➖ (fixes bug) | 44 | 39 | `Tom & Jerry <tag> "q" 'a'\ncafé numeric\n` |
| multi_wt_run | yes | ✅ | 23 | 23 | `first part second part\n` |
| preserve | yes | ✅ | 25 | 25 | `  leading and trailing  \n` |
| self_closing_run | yes | ✅ | 10 | 10 | `keep this\n` |
| simple | yes | ✅ | 31 | 31 | `Hello world.\nSecond paragraph.\n` |
| table | no | ➖ (fixes bug) | 68 | 26 | `intro\ncell A\ncell B\noutro\n` |
| tabs_breaks | no | ➖ (fixes bug) | 52 | 38 | `before\tafter\nnextline-still-same-para\n` |
| unicode | yes | ✅ | 20 | 20 | `café — naïve 😀 end\n` |
