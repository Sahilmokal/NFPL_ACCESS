# One command each. Needs: Docker, JDK 17+, Maven 3.9+.
.PHONY: db seed scale run test report down

db:            ## start MySQL in Docker and wait until it is healthy
	docker compose up -d --wait mysql

seed: db       ## START MySQL + load the SAMPLE data (5 branches, 10 clients, 20 users, ~300 jobs) + run the API on :8080
	mvn -q spring-boot:run -Dspring-boot.run.arguments=--app.seed=sample

scale: db      ## same, but the SCALE case: 500 users, 200,000 jobs (takes ~15 s to load)
	mvn -q spring-boot:run -Dspring-boot.run.arguments=--app.seed=scale

run: db        ## run the API on whatever data is already there
	mvn -q spring-boot:run

test: db       ## run all automated tests (uses its own database, nfpl_test)
	mvn test

report:        ## everyone's access in plain words (needs the API running)
	@cookies=$$(mktemp); \
	curl -s -c "$$cookies" -H "Content-Type: application/json" -d '{"username":"dev","password":"nfpl-demo"}' http://localhost:8080/api/auth/login >/dev/null; \
	curl -s -b "$$cookies" http://localhost:8080/api/access/report | sed -e 's/^\["//' -e 's/"\]$$//' -e 's/","/\n/g'; \
	rm -f "$$cookies"

down:          ## stop MySQL (data is kept in the container until you remove it)
	docker compose down
