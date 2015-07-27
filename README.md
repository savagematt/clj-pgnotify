# clj-pgnotify

Wraps Postgres pg_notify in core.async channels

## Usage

```clj
(with-open [cnxn-subscription (sql/get-connection @db)]
  (let [sub          (start! (pg-subscriber ["my-channel"])
                             cnxn-subscription)]

    (sql/with-db-transaction [cnxn @db]
      (pg-pub! cnxn "my-channel" "hello"))

    (<!! sub)
    ;=> [{:channel "my-channel" :payload "hello"}]
```

For more advanced usage, see documentation on `pg-subscriber`.

## License

Copyright © 2015 Matt Savage

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
