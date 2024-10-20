init:
	pip install -r requirements.txt -r requirements-dev.txt
	cd ../ && mvn clean install -DskipTests && cd ./python
	python setup.py copy_hotvect_jar

test:
	cd ../ && mvn clean install -Pintegration-test && cd ./python
	python setup.py copy_hotvect_jar
	pytest --hypothesis-verbosity=verbose

quick:
	cd ../ && mvn clean install -DskipTests -Dmaven.javadoc.skip=true && cd ./python
	python setup.py copy_hotvect_jar
