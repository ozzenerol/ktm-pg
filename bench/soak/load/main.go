// Mixed load for the soak server where wrk isn't installed: CONNS workers
// for DUR, 80% GET /users/{id}, 10% GET /users, 8% POST, 2% DELETE.
// Prints requests, 5xx/400 answers and transport errors as one JSON line.
//   load -url http://127.0.0.1:9310 -c 64 -d 60s
package main

import (
	"flag"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

func main() {
	base := flag.String("url", "http://127.0.0.1:9310", "server")
	conns := flag.Int("c", 64, "workers")
	dur := flag.Duration("d", 60*time.Second, "how long")
	flag.Parse()
	tr := &http.Transport{MaxIdleConnsPerHost: *conns}
	cl := &http.Client{Transport: tr, Timeout: 5 * time.Second}
	var reqs, bad, errs atomic.Int64
	stop := time.Now().Add(*dur)
	var wg sync.WaitGroup
	for w := 0; w < *conns; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			rnd := rand.New(rand.NewSource(int64(w) + time.Now().UnixNano()))
			for n := 0; time.Now().Before(stop); n++ {
				var req *http.Request
				switch r := rnd.Intn(100); {
				case r < 80:
					req, _ = http.NewRequest("GET", fmt.Sprintf("%s/users/%d", *base, 1+rnd.Intn(10000)), nil)
				case r < 90:
					req, _ = http.NewRequest("GET", *base+"/users", nil)
				case r < 98:
					body := fmt.Sprintf(`{"name":"soak","email":"g%d-%d-%d@x"}`, w, time.Now().UnixNano(), n)
					req, _ = http.NewRequest("POST", *base+"/users", strings.NewReader(body))
				default:
					req, _ = http.NewRequest("DELETE", fmt.Sprintf("%s/users/%d", *base, 10000+rnd.Intn(2000000)), nil)
				}
				resp, err := cl.Do(req)
				reqs.Add(1)
				if err != nil {
					errs.Add(1)
					continue
				}
				io.Copy(io.Discard, resp.Body)
				resp.Body.Close()
				if resp.StatusCode >= 500 || resp.StatusCode == 400 {
					bad.Add(1)
				}
			}
		}(w)
	}
	wg.Wait()
	fmt.Printf("{\"requests\":%d,\"bad\":%d,\"errors\":%d}\n", reqs.Load(), bad.Load(), errs.Load())
}
