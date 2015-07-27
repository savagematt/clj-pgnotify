# clj-pgnotify

Wraps Postgres pg_notify in core.async channels

## Usage

```clj
(with-open [listener-cnxn (sql/get-connection @db)]
  (let [sub (listen! (pg-listener ["my-channel"])
                     listener-cnxn)]

    (sql/with-db-transaction [cnxn @db]
      (pg-notify! cnxn "my-channel" "hello"))

    (<!! sub)
    ;=> [{:channel "my-channel" :payload "hello"}]
    ))
```

For more advanced usage, see documentation on `pg-listener`.

## License

Copyright © 2015 Matt Savage

Distributed under the Eclipse Public License either version 1.0 or (at
your discretion) any later version.
