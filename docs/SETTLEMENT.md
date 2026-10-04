# Settlement proof (Solana devnet)

Run on 2026-10-04 with `scripts/settlement-demo.ts`. Each "morning" lasts 10 minutes so a whole pact settles in about half an hour. Every row links to the transaction on Solana Explorer.

- Pact A "Settlement proof": [DbknW4dV6seEXCWcudbu74DSRKTFcSH7mspNUX1CMhmb](https://explorer.solana.com/address/DbknW4dV6seEXCWcudbu74DSRKTFcSH7mspNUX1CMhmb?cluster=devnet) — vault [EoLx3uBkbcBP6zdRNKhHmcDecaCSii8HsWPp94wTTfho](https://explorer.solana.com/address/EoLx3uBkbcBP6zdRNKhHmcDecaCSii8HsWPp94wTTfho?cluster=devnet)
- Pact B "Refund proof": [4j63WiuP8HsTbaAzbuQscmPJWyaYrNyUR4ycWom5mfcP](https://explorer.solana.com/address/4j63WiuP8HsTbaAzbuQscmPJWyaYrNyUR4ycWom5mfcP?cluster=devnet) — vault [41rbGBqZvXdx1Q5B29i85AZaNMDW36dJPeX8FpNWyANz](https://explorer.solana.com/address/41rbGBqZvXdx1Q5B29i85AZaNMDW36dJPeX8FpNWyANz?cluster=devnet)

## What happened

| Step | Transaction |
| --- | --- |
| Create pact A (3 mornings × 10 min, 10 SKR) | [kMFXba1X5G8L1jza…](https://explorer.solana.com/tx/kMFXba1X5G8L1jzaVZhyEAyA9hM3sk28p1uSkoweeACJssz7q69Xysu5ssYdcgmvSMsSpTGcEDRx4CCD1qhPfN1?cluster=devnet) |
| Aman joins, locks 30 SKR | [55tHxpWstzMVBZ34…](https://explorer.solana.com/tx/55tHxpWstzMVBZ34vL8FqDqw6qhKwS7feNwQiBtsjAGV7QRJqvsA9U4UVpYFdiMYcV8oKMxHLxTpU9Zigbi81HyC?cluster=devnet) |
| Priya joins, locks 30 SKR | [4fX76vWMyw77ivwj…](https://explorer.solana.com/tx/4fX76vWMyw77ivwjTUgyq6bcXrr6pqoBbdJxyXnaXg35nWL7ojNcFQgRsGSAAUK6in5gbxKHwQrXtoUkACFbDPby?cluster=devnet) |
| Mei joins, locks 30 SKR | [5hpPBRrARKJfFTEK…](https://explorer.solana.com/tx/5hpPBRrARKJfFTEKt1uZvs4oE6rK73zSJUMb3rBjAQ5j7HRNDmgdfZbJNcHpY4gRLt8gaKwK6sxBsbKpawpgbNXk?cluster=devnet) |
| Create pact B (1 morning, nobody will wake) | [5F5zZEobz8R7eqt4…](https://explorer.solana.com/tx/5F5zZEobz8R7eqt4Lep8szX1fqS1gZaXFex2MKmUCBJGfncs4ZGewrsC1n19Pbw6qyNGZdiRL79hMF9qoQdFmfje?cluster=devnet) |
| Kofi joins pact B, locks 10 SKR | [5iunnZ2WKcQKXmkX…](https://explorer.solana.com/tx/5iunnZ2WKcQKXmkX28LdgUQjodu9tZMy61Zt5n6RU47ZG3w5VryYMFGEWCShs3vwcDn2tDnauCTLacdhqVZkSRzd?cluster=devnet) |
| Sofia joins pact B, locks 10 SKR | [4g84kACfGphVgB7f…](https://explorer.solana.com/tx/4g84kACfGphVgB7f6T4a9dTmrgjkJnPA7Woh4SNrMwFSKcjehCb2PB941cnDQ3eGR97D5oaR9SaQ8EAEVZAW1vEn?cluster=devnet) |
| Morning 1: Aman clocks in 60s early | [JfZTMZneJbLg58ME…](https://explorer.solana.com/tx/JfZTMZneJbLg58ME84rhtMDg4hLxSffP9bzhFGoJytVS9raoazCWCTi72AtnonJJsXCSsgzEdJzfD3v1qxRFB7x?cluster=devnet) |
| Morning 1: Priya clocks in 20s early | [3igR8BLw7JGzNcAh…](https://explorer.solana.com/tx/3igR8BLw7JGzNcAhquXkwk923RnAzDobqqPu1oNPzsJmuAknXsQTnGmTrErs9zotmor2SkFFQ4cqgEAVocS3pkKE?cluster=devnet) |
| Morning 2: Aman clocks in 90s early | [a79Xv884jrDkk5QB…](https://explorer.solana.com/tx/a79Xv884jrDkk5QBJL2VmS8yo4AAF9wjivq9V6sKBZFBY7UV7m8xjoVybjkYo9V9ePWQ8dzmPe4GZBFwrm1oMdd?cluster=devnet) |
| Morning 2: Mei clocks in 75s late (red ink) | [4z455iWS4FzJp5Ph…](https://explorer.solana.com/tx/4z455iWS4FzJp5PhsYqpiPE4SUvbqQJqYWLdQ4jc3X4cMhS61Pg4jrCwGQcboysBSQL5GDV8zXm6KQYBwn7RJyXv?cluster=devnet) |
| Morning 3: Aman clocks in 30s early | [21RJTUnASmjeYQEH…](https://explorer.solana.com/tx/21RJTUnASmjeYQEHXAZctVaLVr4gXhvT4hWVdw2pfsZmoKDg7UrrVLeuX3MszRgXdpsu56Lr3iKoHqefMAsw1Ays?cluster=devnet) |
| Morning 1: Mei sleeps in | no transaction, window closes |
| Morning 2: Priya sleeps in | no transaction, window closes |
| Morning 3: Priya and Mei sleep in | no transaction, windows close |
| Early claim before the pact ends | rejected by the program (`NotOver`) |
| Pact B: Kofi claims refund (+10 SKR) | [3s8QFKqPeh7DUmS3…](https://explorer.solana.com/tx/3s8QFKqPeh7DUmS3b7F1CP4xdcwtBcnMTivShCtUxgXmRAHctagJ2496HW6GWcRUTKHRZRPSVQxKGXy1kNDB5DNR?cluster=devnet) |
| Pact B: Sofia claims refund (+10 SKR) | [98k35ji4HHMgUh1y…](https://explorer.solana.com/tx/98k35ji4HHMgUh1y3vykEvB2k2wPdFVoffDCLBUeTCDXXN5Agfk4qRKs5gTa8c28F4nkoyiTJeq2CtbgyvDR8uY?cluster=devnet) |
| Pact A: Aman claims +54 SKR | [FvWXhv7nteJnJ3fd…](https://explorer.solana.com/tx/FvWXhv7nteJnJ3fdjGZKWEuNQTKkmYzZUM4iDezKGtojg35AdBPXPDTZGzEURCiEGfc4rEFNzcpnNF66HNsW1pP?cluster=devnet) |
| Pact A: Priya claims +18 SKR | [4MvZsRn6ZZfrpKT9…](https://explorer.solana.com/tx/4MvZsRn6ZZfrpKT9VVwq7Xb4JtVWNyD56nexZpxiXXv3VCuDuEHPN2cY9yfVVAW5JiZEukiS1gpjEvdPWtHzq26?cluster=devnet) |
| Pact A: Mei claims +18 SKR | [47AamCWf5eeUyUSF…](https://explorer.solana.com/tx/47AamCWf5eeUyUSFe96ohjh6b8CfexeiFTAx1Ua2D4oa93arjpkUaKWmMvoPbw7YJkrhWnSweGVfFfnxqK1Lkxbp?cluster=devnet) |

## Conservation check

| | Expected | On chain |
| --- | --- | --- |
| Pact A locked | 90 SKR | 90 SKR (vault after joins) |
| Mornings kept | Aman 3, Priya 1, Mei 1 = 5 | total_hits = 5 |
| Pot (locked − 10 × kept) | 40 SKR | — |
| Aman: 3 × 10 + 40 × 3/5 | 54 SKR | 54 SKR |
| Priya: 1 × 10 + 40 × 1/5 | 18 SKR | 18 SKR |
| Mei: 1 × 10 + 40 × 1/5 | 18 SKR | 18 SKR |
| Total paid out | 90 SKR | 90 SKR (total_paid = 90) |
| Pact A vault after settlement | 0 | 0 |
| Pact B (nobody woke) refunds | 10 + 10 | vault after = 0 |
