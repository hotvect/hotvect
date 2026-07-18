init:
	uv sync --all-extras --group dev
	cd ../ && mvn clean install -DskipTests && cd ./python
	uv run scripts/copy_jar.py

test:
	uv sync --all-extras --group dev
	cd ../ && mvn clean install -Pintegration-test && cd ./python
	uv run scripts/copy_jar.py
	uv run pytest --hypothesis-verbosity=verbose

quick:
	cd ../ && mvn clean install -Dmaven.test.skip=true -Dmaven.javadoc.skip=true && cd ./python
	uv run scripts/copy_jar.py

docs-build:
	bash scripts/docs.sh build

docs-serve:
	bash scripts/docs.sh serve

bump-version:
	@if [ -z "$(VERSION)" ]; then \
		echo "Usage: make bump-version VERSION=<major|minor|patch|X.Y.Z>"; \
		exit 1; \
	fi
	uv run scripts/bump_versions.py "$(VERSION)"
	uv lock
