import json
import logging
import os
from datetime import datetime, tzinfo, timedelta
from shutil import copyfile
from typing import Dict, List, Any

import hotvect.mlutils as mlutils
import pandas as pd
from hotvect.utils import trydelete, runshell, read_json, to_zip_archive, ensure_file_exists, clean_dir, \
    ensure_dir_exists

logging.basicConfig(level=logging.WARNING)


# Courtesy of https://stackoverflow.com/a/23705687/234901
# Under CC BY-SA 3.0
class SimpleUTC(tzinfo):
    def tzname(self, **kwargs):
        return "UTC"

    def utcoffset(self, dt):
        return timedelta(0)


class Hotvect:
    def __init__(self,
                 hotvect_util_jar_path: str,
                 algorithm_jar_path: str,
                 metadata_base_path: str,
                 output_base_path: str,
                 train_data_path: str,
                 validation_data_path: str,
                 algorithm_definition: Dict[str, Any],
                 state_source_base_path: str = None,
                 run_id: str = "default",
                 enable_gzip: bool = True,
                 jvm_options: List[str] = None
                 ):
        self.algorithm_definition = algorithm_definition
        self.run_id: str = run_id
        self.ran_at: str = datetime.utcnow().replace(tzinfo=SimpleUTC()).isoformat()

        # Utilities
        self.hotvect_util_jar_path = hotvect_util_jar_path

        # Algorithm classes
        self.algorithm_jar_path = algorithm_jar_path

        # Metadata
        self.metadata_base_path = metadata_base_path

        # Output
        self.output_base_path = output_base_path

        # Train data
        self.train_data_path = train_data_path
        ensure_file_exists(train_data_path)

        # Validation data
        self.validation_data_location = validation_data_path
        ensure_file_exists(validation_data_path)

        # State source data
        self.state_source_base_path = state_source_base_path
        if self.state_source_base_path:
            ensure_dir_exists(self.state_source_base_path)
        self.feature_states: Dict[str, str] = {}

        # Gzip
        self.enable_gzip = enable_gzip

        # Jvm options
        if not jvm_options:
            self.jvm_options = ['-Xmx4g']
        else:
            self.jvm_options = jvm_options

        logging.info(f'Initialized:{self.__dict__}')

    def set_run_id(self, run_id: str):
        self.run_id = run_id

    def set_algorithm_definition(self, algorithm_definition: Dict[str, Any]):
        self.algorithm_definition = algorithm_definition

    def metadata_path(self) -> str:
        return os.path.join(self.metadata_base_path, self.algorithm_definition['algorithm_name'], self.run_id)

    def output_path(self) -> str:
        return os.path.join(self.output_base_path, self.algorithm_definition['algorithm_name'], self.run_id)

    def state_output_path(self, state_name: str):
        state_filename = f'{state_name}'
        return os.path.join(
            self.output_path(),
            state_filename
        )

    def encoded_data_file_path(self) -> str:
        encode_suffix = 'encoded.gz' if self.enable_gzip else 'encoded'
        return os.path.join(
            self.output_path(),
            encode_suffix
        )

    def model_file_path(self) -> str:
        return os.path.join(
            self.output_path(),
            'model.parameter'
        )

    def score_file_path(self) -> str:
        return os.path.join(
            self.output_path(),
            'validation_scores.csv'
        )

    def audit_data_file_path(self) -> str:
        encode_suffix = 'audit.jsonl.gz' if self.enable_gzip else 'audit.jsonl'
        return os.path.join(
            self.output_path(),
            encode_suffix
        )

    def predict_parameter_file_path(self) -> str:
        return os.path.join(
            self.output_path(),
            f"{self.algorithm_definition['algorithm_name']}@{self.run_id}.parameters.zip"
        )

    def encode_parameter_file_path(self) -> str:
        return os.path.join(
            self.output_path(),
            'encode.parameters.zip'
        )

    def _write_algorithm_definition(self) -> str:
        """Write algorithm definition so that Java can read it"""
        algorithm_definition_path = os.path.join(
            self.metadata_path(),
            'algorithm_definition.json'
        )
        trydelete(algorithm_definition_path)
        with open(algorithm_definition_path, 'w') as fp:
            json.dump(self.algorithm_definition, fp)

        return algorithm_definition_path

    def _write_data(self, data: Dict, dest_file_name: str) -> str:
        """Write algorithm definition so that Java can read it"""
        dest = os.path.join(
            self.output_path(),
            dest_file_name
        )
        trydelete(dest)
        with open(dest, 'w') as fp:
            json.dump(data, fp)

        return dest

    def clean(self) -> None:
        for file in [
            self.encoded_data_file_path(),
            self.model_file_path(),
            self.score_file_path()
        ]:
            trydelete(file)

        for directory in [
            self.metadata_path(),
            self.output_path(),
        ]:
            clean_dir(directory)
        logging.info('Cleaned output and metadata')

    def run_all(self, run_id=None, clean=True) -> Dict:
        if run_id:
            self.run_id = run_id

        result = {
            'algorithm_name': self.algorithm_definition['algorithm_name'],
            'run_id': self.run_id,
            'ran_at': self.ran_at,
            'algorithm_definition': self.algorithm_definition
        }
        self.clean()
        result['states'] = self.generate_states()
        result['package_encode_params'] = self.package_encode_parameters()
        result['encode'] = self.encode()
        result['train'] = self.train()
        result['package_predict_params'] = self.package_predict_parameters()
        result['predict'] = self.predict()
        result['evaluate'] = self.evaluate()
        if clean:
            self.clean_output()
        return result

    def _base_command(self, metadata_location: str) -> List[str]:
        ret = [
            'java',
            '-cp', f"{self.hotvect_util_jar_path}",
        ]
        ret.extend(self.jvm_options)
        ret.extend(['com.eshioji.hotvect.offlineutils.commandline.Main',
                    '--algorithm-jar', f'{self.algorithm_jar_path}',
                    '--algorithm-definition', self._write_algorithm_definition(),
                    '--meta-data', metadata_location])
        return ret

    def generate_states(self) -> Dict:
        states = self.algorithm_definition.get('vectorizer_parameters', {}).get('feature_states', {})
        metadata = {}
        for state_name, instruction in states.items():
            metadata_path = os.path.join(self.metadata_path(), f'generate-state-{state_name}.json')
            trydelete(metadata_path)

            output_path = self.state_output_path(state_name)
            trydelete(output_path)

            source_path = os.path.join(self.state_source_base_path, instruction['source_name'])
            ensure_file_exists(source_path)
            generation_task = instruction['generation_task']

            if instruction.get('cache'):
                cached = os.path.join(self.state_source_base_path, instruction['cache'])
                ensure_file_exists(cached)
                logging.info(f'Using cache for state:{state_name} from {cached}')
                copyfile(cached, output_path)
                metadata[state_name] = {
                    'cache': cached
                }
            else:
                logging.info(f'No cache found for state:{state_name}, generating')
                cmd = self._base_command(metadata_path)
                cmd.extend(['--generate-state', generation_task,
                            '--training-data', self.train_data_path,
                            '--source', source_path,
                            '--dest', output_path])
                runshell(cmd)
                metadata[state_name] = read_json(metadata_path)
            self.feature_states[state_name] = output_path

        return metadata

    def package_encode_parameters(self) -> Dict:
        encode_parameter_package_location = self.encode_parameter_file_path()
        trydelete(encode_parameter_package_location)

        to_package = list(self.feature_states.values())

        to_zip_archive(to_package, encode_parameter_package_location)
        return {
            'sources': to_package,
            'package': encode_parameter_package_location
        }

    def encode(self) -> Dict:
        metadata_location = os.path.join(self.metadata_path(), 'encode_metadata.json')
        trydelete(metadata_location)

        encoded_data_location = self.encoded_data_file_path()
        trydelete(encoded_data_location)

        cmd = self._base_command(metadata_location)
        cmd.append('--encode')
        if self.feature_states:
            # We have feature states
            cmd.extend(['--parameters', self.encode_parameter_file_path()])
        cmd.extend(['--source', self.train_data_path])
        cmd.extend(['--dest', encoded_data_location])
        runshell(cmd)
        return read_json(metadata_location)

    def train(self) -> Dict:
        metadata_location = os.path.join(self.metadata_path(), 'train_metadata.json')
        trydelete(metadata_location)

        model_location = self.model_file_path()
        trydelete(model_location)

        cmd = [
            'vw', self.encoded_data_file_path(),
            '--readable_model', model_location,
            '--noconstant',
            '--loss_function', 'logistic',
        ]
        train_params = self.algorithm_definition['training_parameters']
        cmd.extend(train_params)
        train_log = runshell(cmd)
        metadata = {
            'training_parameters': train_params,
            'train_log': train_log
        }
        with open(metadata_location, 'w') as f:
            json.dump(metadata, f)
        return metadata

    def package_predict_parameters(self) -> Dict:
        predict_parameter_package_location = self.predict_parameter_file_path()
        trydelete(predict_parameter_package_location)

        # Add the model file
        to_package = [self.model_file_path()]

        # Add all the feature states
        to_package.extend(list(self.feature_states.values()))

        # Add the algo parameters
        algorithm_parameters = {
            'algorithm_name': self.algorithm_definition['algorithm_name'],
            'parameter_id': self.run_id,
            'ran_at': self.ran_at,
            'algorithm_definition': self.algorithm_definition,
            'sources': to_package,
            'package': predict_parameter_package_location
        }
        algo_parameters_path = self._write_data(algorithm_parameters, 'algorithm_parameters.json')
        to_package.append(algo_parameters_path)

        to_zip_archive(to_package, predict_parameter_package_location)
        return algorithm_parameters

    def predict(self) -> Dict:
        metadata_location = os.path.join(self.metadata_path(), 'predict_metadata.json')
        trydelete(metadata_location)

        score_location = self.score_file_path()
        trydelete(score_location)

        cmd = self._base_command(metadata_location)
        cmd.append('--predict')
        cmd.extend(['--source', self.validation_data_location])
        cmd.extend(['--dest', score_location])
        cmd.extend(['--parameters', self.predict_parameter_file_path()])
        runshell(cmd)
        return read_json(metadata_location)

    def evaluate(self) -> Dict:
        metadata_location = os.path.join(self.metadata_path(), 'evaluate_metadata.json')
        trydelete(metadata_location)

        df = pd.read_csv(self.score_file_path(), header=None)
        lower_auc, mean_auc, upper_auc = mlutils.bootstrap_roc_auc(df[1], df[0])

        meta_data = {
            'upper_auc': upper_auc,
            'mean_auc': mean_auc,
            'lower_auc': lower_auc
        }
        with open(metadata_location, 'w') as f:
            json.dump(meta_data, f)
        return meta_data

    def clean_output(self) -> None:
        trydelete(self.encoded_data_file_path())
        trydelete(self.model_file_path())
        trydelete(self.score_file_path())
        trydelete(self.output_path())

    def audit(self) -> None:
        metadata_location = os.path.join(self.metadata_path(), 'audit_metadata.json')
        trydelete(metadata_location)

        audit_data_location = self.audit_data_file_path()
        trydelete(audit_data_location)

        cmd = self._base_command(metadata_location)
        cmd.append('--audit')
        if self.feature_states:
            # We have feature states
            cmd.extend(['--parameters', self.encode_parameter_file_path()])
        cmd.extend(['--source', self.train_data_path])
        cmd.extend(['--dest', audit_data_location])
        runshell(cmd)
        return read_json(metadata_location)
