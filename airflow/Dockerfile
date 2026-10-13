FROM apache/airflow:3.3.2-python3.12

ADD requirements.txt /tmp/requirements.txt
# Пиним apache-airflow, как в официальных рекомендациях, чтобы установка
# зависимостей не затронула версию самого Airflow из образа
RUN pip install --no-cache-dir apache-airflow==3.3.2 -r /tmp/requirements.txt
