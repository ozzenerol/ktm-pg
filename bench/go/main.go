// Go pgx's side of ktm-pg's benchmark (../pgbench.ktm): the same three
// queries for a fixed time, CONC goroutines sharing a pgxpool of CONC
// connections. Prints one JSON line.
//   pgbench-go URL MODE CONC SECONDS
package main

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type User struct {
	ID    int64
	Name  string
	Email string
}

func main() {
	if len(os.Args) < 5 {
		fmt.Println("usage: pgbench-go URL MODE CONC SECONDS")
		os.Exit(2)
	}
	url, mode := os.Args[1], os.Args[2]
	conc, _ := strconv.Atoi(os.Args[3])
	secs, _ := strconv.Atoi(os.Args[4])
	ctx := context.Background()
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		panic(err)
	}
	cfg.MaxConns = int32(conc)
	db, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		panic(err)
	}
	if err := db.Ping(ctx); err != nil {
		panic(err)
	}
	var ops, errs atomic.Int64
	stop := time.Now().Add(time.Duration(secs) * time.Second)
	start := time.Now()
	var wg sync.WaitGroup
	for w := 0; w < conc; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			seed := int64(w*1000)*7919 + 1
			var n int64
			for time.Now().Before(stop) {
				var err error
				switch mode {
				case "select":
					seed = (seed*1103515245 + 12345) & 0x7FFFFFFF
					var u User
					err = db.QueryRow(ctx, "SELECT id, name, email FROM bench_users WHERE id = $1", 1+seed%10000).Scan(&u.ID, &u.Name, &u.Email)
				case "insert":
					_, err = db.Exec(ctx, "INSERT INTO bench_ins (name, n) VALUES ($1, $2)", "someone", n)
				default:
					rows, qerr := db.Query(ctx, "SELECT id, name, email FROM bench_users WHERE id <= 1000")
					if qerr != nil {
						err = qerr
						break
					}
					us := make([]User, 0, 1000)
					for rows.Next() {
						var u User
						if err = rows.Scan(&u.ID, &u.Name, &u.Email); err != nil {
							break
						}
						us = append(us, u)
					}
					rows.Close()
					if err == nil {
						err = rows.Err()
					}
				}
				if err != nil {
					errs.Add(1)
				} else {
					n++
				}
			}
			ops.Add(n)
		}(w)
	}
	wg.Wait()
	took := time.Since(start).Seconds()
	fmt.Printf("{\"client\":\"pgx\",\"mode\":\"%s\",\"conc\":%d,\"ops\":%d,\"errors\":%d,\"secs\":%.3f,\"ops_s\":%.0f}\n",
		mode, conc, ops.Load(), errs.Load(), took, float64(ops.Load())/took)
	db.Close()
}
