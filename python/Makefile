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
