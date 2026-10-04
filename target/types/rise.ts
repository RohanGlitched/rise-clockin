/**
 * Program IDL in camelCase format in order to be used in JS/TS.
 *
 * Note that this is only a type helper and is not the actual IDL. The original
 * IDL can be found at `target/idl/rise.json`.
 */
export type Rise = {
  "address": "6kQL7PccHpE7yUrsq5TgxFgQc7K7FVbUJShRUPbWfCdS",
  "metadata": {
    "name": "rise",
    "version": "0.1.0",
    "spec": "0.1.0",
    "description": "Rise: a wake-up alarm with stakes. Clock in on Solana before your friends do."
  },
  "instructions": [
    {
      "name": "claim",
      "docs": [
        "After the pact ends, pays the member their kept stakes plus their share of the pot."
      ],
      "discriminator": [
        62,
        198,
        214,
        193,
        213,
        159,
        108,
        210
      ],
      "accounts": [
        {
          "name": "owner",
          "writable": true,
          "signer": true,
          "relations": [
            "member"
          ]
        },
        {
          "name": "pact",
          "writable": true,
          "relations": [
            "member"
          ]
        },
        {
          "name": "member",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  109,
                  101,
                  109,
                  98,
                  101,
                  114
                ]
              },
              {
                "kind": "account",
                "path": "pact"
              },
              {
                "kind": "account",
                "path": "owner"
              }
            ]
          }
        },
        {
          "name": "mint",
          "relations": [
            "pact"
          ]
        },
        {
          "name": "userAta",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "account",
                "path": "owner"
              },
              {
                "kind": "account",
                "path": "tokenProgram"
              },
              {
                "kind": "account",
                "path": "mint"
              }
            ],
            "program": {
              "kind": "const",
              "value": [
                140,
                151,
                37,
                143,
                78,
                36,
                137,
                241,
                187,
                61,
                16,
                41,
                20,
                142,
                13,
                131,
                11,
                90,
                19,
                153,
                218,
                255,
                16,
                132,
                4,
                142,
                123,
                216,
                219,
                233,
                248,
                89
              ]
            }
          }
        },
        {
          "name": "vault",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "account",
                "path": "pact"
              },
              {
                "kind": "account",
                "path": "tokenProgram"
              },
              {
                "kind": "account",
                "path": "mint"
              }
            ],
            "program": {
              "kind": "const",
              "value": [
                140,
                151,
                37,
                143,
                78,
                36,
                137,
                241,
                187,
                61,
                16,
                41,
                20,
                142,
                13,
                131,
                11,
                90,
                19,
                153,
                218,
                255,
                16,
                132,
                4,
                142,
                123,
                216,
                219,
                233,
                248,
                89
              ]
            }
          }
        },
        {
          "name": "tokenProgram"
        },
        {
          "name": "associatedTokenProgram",
          "address": "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
        },
        {
          "name": "systemProgram",
          "address": "11111111111111111111111111111111"
        }
      ],
      "args": []
    },
    {
      "name": "clockIn",
      "docs": [
        "Clocks in for today. Counts only inside the member's wake window:",
        "from 30 minutes before the wake time until the pact's grace period after it."
      ],
      "discriminator": [
        206,
        150,
        221,
        145,
        122,
        124,
        77,
        89
      ],
      "accounts": [
        {
          "name": "owner",
          "signer": true,
          "relations": [
            "member"
          ]
        },
        {
          "name": "pact",
          "writable": true,
          "relations": [
            "member"
          ]
        },
        {
          "name": "member",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  109,
                  101,
                  109,
                  98,
                  101,
                  114
                ]
              },
              {
                "kind": "account",
                "path": "pact"
              },
              {
                "kind": "account",
                "path": "owner"
              }
            ]
          }
        }
      ],
      "args": [
        {
          "name": "mission",
          "type": "u8"
        }
      ]
    },
    {
      "name": "createPact",
      "docs": [
        "Starts a pact. `start_ts` is the start of day 0 (the app uses UTC midnight)."
      ],
      "discriminator": [
        59,
        173,
        189,
        6,
        111,
        116,
        205,
        217
      ],
      "accounts": [
        {
          "name": "creator",
          "writable": true,
          "signer": true
        },
        {
          "name": "pact",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  112,
                  97,
                  99,
                  116
                ]
              },
              {
                "kind": "account",
                "path": "creator"
              },
              {
                "kind": "arg",
                "path": "seed"
              }
            ]
          }
        },
        {
          "name": "mint"
        },
        {
          "name": "vault",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "account",
                "path": "pact"
              },
              {
                "kind": "account",
                "path": "tokenProgram"
              },
              {
                "kind": "account",
                "path": "mint"
              }
            ],
            "program": {
              "kind": "const",
              "value": [
                140,
                151,
                37,
                143,
                78,
                36,
                137,
                241,
                187,
                61,
                16,
                41,
                20,
                142,
                13,
                131,
                11,
                90,
                19,
                153,
                218,
                255,
                16,
                132,
                4,
                142,
                123,
                216,
                219,
                233,
                248,
                89
              ]
            }
          }
        },
        {
          "name": "tokenProgram"
        },
        {
          "name": "associatedTokenProgram",
          "address": "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
        },
        {
          "name": "systemProgram",
          "address": "11111111111111111111111111111111"
        }
      ],
      "args": [
        {
          "name": "seed",
          "type": "u64"
        },
        {
          "name": "name",
          "type": "string"
        },
        {
          "name": "stakePerDay",
          "type": "u64"
        },
        {
          "name": "startTs",
          "type": "i64"
        },
        {
          "name": "daySecs",
          "type": "u32"
        },
        {
          "name": "days",
          "type": "u16"
        },
        {
          "name": "graceSecs",
          "type": "u16"
        },
        {
          "name": "maxMembers",
          "type": "u16"
        },
        {
          "name": "public",
          "type": "bool"
        }
      ]
    },
    {
      "name": "drip",
      "docs": [
        "Sends test SKR to the caller, at most once an hour per wallet."
      ],
      "discriminator": [
        215,
        250,
        141,
        179,
        116,
        10,
        187,
        192
      ],
      "accounts": [
        {
          "name": "user",
          "writable": true,
          "signer": true
        },
        {
          "name": "faucet",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  102,
                  97,
                  117,
                  99,
                  101,
                  116
                ]
              }
            ]
          }
        },
        {
          "name": "ticket",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  100,
                  114,
                  105,
                  112
                ]
              },
              {
                "kind": "account",
                "path": "user"
              }
            ]
          }
        },
        {
          "name": "mintAuth",
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  109,
                  105,
                  110,
                  116,
                  95,
                  97,
                  117,
                  116,
                  104
                ]
              }
            ]
          }
        },
        {
          "name": "mint",
          "writable": true,
          "relations": [
            "faucet"
          ]
        },
        {
          "name": "userAta",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "account",
                "path": "user"
              },
              {
                "kind": "account",
                "path": "tokenProgram"
              },
              {
                "kind": "account",
                "path": "mint"
              }
            ],
            "program": {
              "kind": "const",
              "value": [
                140,
                151,
                37,
                143,
                78,
                36,
                137,
                241,
                187,
                61,
                16,
                41,
                20,
                142,
                13,
                131,
                11,
                90,
                19,
                153,
                218,
                255,
                16,
                132,
                4,
                142,
                123,
                216,
                219,
                233,
                248,
                89
              ]
            }
          }
        },
        {
          "name": "tokenProgram"
        },
        {
          "name": "associatedTokenProgram",
          "address": "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
        },
        {
          "name": "systemProgram",
          "address": "11111111111111111111111111111111"
        }
      ],
      "args": []
    },
    {
      "name": "initFaucet",
      "docs": [
        "One-time setup of the faucet for the test SKR mint. The mint's authority",
        "must already be the program's `mint_auth` PDA."
      ],
      "discriminator": [
        122,
        64,
        137,
        151,
        7,
        139,
        100,
        57
      ],
      "accounts": [
        {
          "name": "payer",
          "writable": true,
          "signer": true
        },
        {
          "name": "faucet",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  102,
                  97,
                  117,
                  99,
                  101,
                  116
                ]
              }
            ]
          }
        },
        {
          "name": "mintAuth",
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  109,
                  105,
                  110,
                  116,
                  95,
                  97,
                  117,
                  116,
                  104
                ]
              }
            ]
          }
        },
        {
          "name": "mint"
        },
        {
          "name": "tokenProgram"
        },
        {
          "name": "systemProgram",
          "address": "11111111111111111111111111111111"
        }
      ],
      "args": []
    },
    {
      "name": "join",
      "docs": [
        "Joins a pact with a wake time, given as seconds after the start of each pact day.",
        "The member's first day is the first one whose wake window has not opened yet,",
        "and the deposit covers the remaining days."
      ],
      "discriminator": [
        206,
        55,
        2,
        106,
        113,
        220,
        17,
        163
      ],
      "accounts": [
        {
          "name": "owner",
          "writable": true,
          "signer": true
        },
        {
          "name": "pact",
          "writable": true
        },
        {
          "name": "member",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  109,
                  101,
                  109,
                  98,
                  101,
                  114
                ]
              },
              {
                "kind": "account",
                "path": "pact"
              },
              {
                "kind": "account",
                "path": "owner"
              }
            ]
          }
        },
        {
          "name": "mint",
          "relations": [
            "pact"
          ]
        },
        {
          "name": "userAta",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "account",
                "path": "owner"
              },
              {
                "kind": "account",
                "path": "tokenProgram"
              },
              {
                "kind": "account",
                "path": "mint"
              }
            ],
            "program": {
              "kind": "const",
              "value": [
                140,
                151,
                37,
                143,
                78,
                36,
                137,
                241,
                187,
                61,
                16,
                41,
                20,
                142,
                13,
                131,
                11,
                90,
                19,
                153,
                218,
                255,
                16,
                132,
                4,
                142,
                123,
                216,
                219,
                233,
                248,
                89
              ]
            }
          }
        },
        {
          "name": "vault",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "account",
                "path": "pact"
              },
              {
                "kind": "account",
                "path": "tokenProgram"
              },
              {
                "kind": "account",
                "path": "mint"
              }
            ],
            "program": {
              "kind": "const",
              "value": [
                140,
                151,
                37,
                143,
                78,
                36,
                137,
                241,
                187,
                61,
                16,
                41,
                20,
                142,
                13,
                131,
                11,
                90,
                19,
                153,
                218,
                255,
                16,
                132,
                4,
                142,
                123,
                216,
                219,
                233,
                248,
                89
              ]
            }
          }
        },
        {
          "name": "tokenProgram"
        },
        {
          "name": "systemProgram",
          "address": "11111111111111111111111111111111"
        }
      ],
      "args": [
        {
          "name": "name",
          "type": "string"
        },
        {
          "name": "avatar",
          "type": "u8"
        },
        {
          "name": "wakeOffset",
          "type": "u32"
        }
      ]
    }
  ],
  "accounts": [
    {
      "name": "dripTicket",
      "discriminator": [
        62,
        168,
        252,
        99,
        63,
        173,
        192,
        204
      ]
    },
    {
      "name": "faucet",
      "discriminator": [
        146,
        11,
        249,
        142,
        199,
        197,
        61,
        0
      ]
    },
    {
      "name": "member",
      "discriminator": [
        54,
        19,
        162,
        21,
        29,
        166,
        17,
        198
      ]
    },
    {
      "name": "pact",
      "discriminator": [
        34,
        182,
        77,
        169,
        228,
        191,
        31,
        142
      ]
    }
  ],
  "events": [
    {
      "name": "claimed",
      "discriminator": [
        217,
        192,
        123,
        72,
        108,
        150,
        248,
        33
      ]
    },
    {
      "name": "clockedIn",
      "discriminator": [
        126,
        191,
        159,
        12,
        150,
        198,
        98,
        132
      ]
    },
    {
      "name": "joined",
      "discriminator": [
        16,
        20,
        44,
        48,
        132,
        189,
        68,
        98
      ]
    },
    {
      "name": "pactCreated",
      "discriminator": [
        239,
        176,
        11,
        217,
        89,
        215,
        179,
        255
      ]
    }
  ],
  "errors": [
    {
      "code": 6000,
      "name": "badName",
      "msg": "Name must be 1 to 32 characters"
    },
    {
      "code": 6001,
      "name": "badStake",
      "msg": "Stake per day must be more than zero"
    },
    {
      "code": 6002,
      "name": "badDays",
      "msg": "A pact lasts 1 to 64 days"
    },
    {
      "code": 6003,
      "name": "badDayLength",
      "msg": "A pact day must be at least 10 minutes"
    },
    {
      "code": 6004,
      "name": "badGrace",
      "msg": "Grace period must be 1 to 60 minutes"
    },
    {
      "code": 6005,
      "name": "badMembers",
      "msg": "A pact has 2 to 500 members"
    },
    {
      "code": 6006,
      "name": "startInPast",
      "msg": "The pact cannot start in the past"
    },
    {
      "code": 6007,
      "name": "badWake",
      "msg": "Wake time is outside the pact day"
    },
    {
      "code": 6008,
      "name": "pactFull",
      "msg": "This pact is full"
    },
    {
      "code": 6009,
      "name": "pactOver",
      "msg": "This pact has no days left to join"
    },
    {
      "code": 6010,
      "name": "windowClosed",
      "msg": "Your wake window is not open"
    },
    {
      "code": 6011,
      "name": "notYourDay",
      "msg": "This day is not part of your pact"
    },
    {
      "code": 6012,
      "name": "alreadyIn",
      "msg": "You already clocked in today"
    },
    {
      "code": 6013,
      "name": "notOver",
      "msg": "The pact has not ended yet"
    },
    {
      "code": 6014,
      "name": "alreadyClaimed",
      "msg": "You already claimed your payout"
    },
    {
      "code": 6015,
      "name": "dripTooSoon",
      "msg": "Test SKR can be claimed once an hour"
    },
    {
      "code": 6016,
      "name": "overflow",
      "msg": "Amount too large"
    },
    {
      "code": 6017,
      "name": "unsafeMint",
      "msg": "This token has extensions that could move or freeze pact funds"
    }
  ],
  "types": [
    {
      "name": "claimed",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "pact",
            "type": "pubkey"
          },
          {
            "name": "owner",
            "type": "pubkey"
          },
          {
            "name": "amount",
            "type": "u64"
          },
          {
            "name": "hits",
            "type": "u16"
          }
        ]
      }
    },
    {
      "name": "clockedIn",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "pact",
            "type": "pubkey"
          },
          {
            "name": "owner",
            "type": "pubkey"
          },
          {
            "name": "day",
            "type": "u16"
          },
          {
            "name": "delta",
            "type": "i16"
          },
          {
            "name": "mission",
            "type": "u8"
          },
          {
            "name": "streak",
            "type": "u16"
          },
          {
            "name": "ts",
            "type": "i64"
          }
        ]
      }
    },
    {
      "name": "dripTicket",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "lastTs",
            "type": "i64"
          },
          {
            "name": "bump",
            "type": "u8"
          }
        ]
      }
    },
    {
      "name": "faucet",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "mint",
            "type": "pubkey"
          },
          {
            "name": "drips",
            "type": "u64"
          },
          {
            "name": "bump",
            "type": "u8"
          }
        ]
      }
    },
    {
      "name": "joined",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "pact",
            "type": "pubkey"
          },
          {
            "name": "owner",
            "type": "pubkey"
          },
          {
            "name": "firstDay",
            "type": "u16"
          },
          {
            "name": "deposit",
            "type": "u64"
          }
        ]
      }
    },
    {
      "name": "member",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "pact",
            "type": "pubkey"
          },
          {
            "name": "owner",
            "type": "pubkey"
          },
          {
            "name": "name",
            "type": "string"
          },
          {
            "name": "avatar",
            "type": "u8"
          },
          {
            "name": "wakeOffset",
            "type": "u32"
          },
          {
            "name": "firstDay",
            "type": "u16"
          },
          {
            "name": "deposit",
            "type": "u64"
          },
          {
            "name": "hits",
            "type": "u16"
          },
          {
            "name": "streak",
            "type": "u16"
          },
          {
            "name": "bestStreak",
            "type": "u16"
          },
          {
            "name": "lastHitDay",
            "type": "i32"
          },
          {
            "name": "claimed",
            "type": "bool"
          },
          {
            "name": "joinedTs",
            "type": "i64"
          },
          {
            "name": "offsets",
            "docs": [
              "Seconds from the wake time to the clock-in for each day (negative = early);",
              "`NOT_IN` when the member did not clock in."
            ],
            "type": {
              "array": [
                "i16",
                64
              ]
            }
          },
          {
            "name": "bump",
            "type": "u8"
          }
        ]
      }
    },
    {
      "name": "pact",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "creator",
            "type": "pubkey"
          },
          {
            "name": "seed",
            "type": "u64"
          },
          {
            "name": "name",
            "type": "string"
          },
          {
            "name": "mint",
            "type": "pubkey"
          },
          {
            "name": "stakePerDay",
            "type": "u64"
          },
          {
            "name": "startTs",
            "type": "i64"
          },
          {
            "name": "daySecs",
            "type": "u32"
          },
          {
            "name": "days",
            "type": "u16"
          },
          {
            "name": "graceSecs",
            "type": "u16"
          },
          {
            "name": "maxMembers",
            "type": "u16"
          },
          {
            "name": "memberCount",
            "type": "u16"
          },
          {
            "name": "public",
            "type": "bool"
          },
          {
            "name": "totalDeposited",
            "type": "u64"
          },
          {
            "name": "totalHits",
            "type": "u32"
          },
          {
            "name": "totalPaid",
            "type": "u64"
          },
          {
            "name": "claims",
            "type": "u16"
          },
          {
            "name": "bump",
            "type": "u8"
          }
        ]
      }
    },
    {
      "name": "pactCreated",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "pact",
            "type": "pubkey"
          },
          {
            "name": "creator",
            "type": "pubkey"
          },
          {
            "name": "days",
            "type": "u16"
          },
          {
            "name": "stakePerDay",
            "type": "u64"
          }
        ]
      }
    }
  ]
};
