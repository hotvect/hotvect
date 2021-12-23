import copy
import json
from typing import List, Dict

from hotvect.pyhotvect import Hotvect


class FeatureSelectionTask:
    def __init__(self,
                 hotvect: Hotvect,
                 base_algorithm_definition: Dict,
                 anchor_features: List[List[str]],
                 candidate_features: List[List[str]],
                 result_path: str
                 ):
        self.hotvect = hotvect
        self.base_algorithm_definition = base_algorithm_definition
        self.anchor_features = anchor_features
        self.candidate_features = candidate_features
        self.result_path = result_path

    def _build_algorighm_definition(self, name_suffix: str, features: List[List[str]]) -> Dict:
        algo_def = copy.deepcopy(self.base_algorithm_definition)
        algorithm_name = f'{algo_def["algorithm_name"]}-{name_suffix}'
        algo_def['algorithm_name'] = algorithm_name
        algo_def['vectorizer_parameters'] = {
            'features': features
        }
        return algo_def

    def advance(self,
                anchor_name: str,
                anchor_features: List[List[str]],
                candidate_features: List[List[str]]):
        results = {}
        for idx, next_feature in enumerate(candidate_features):
            name = f'anchor_features-{anchor_name}-incremental_feature-{idx}'
            features = copy.deepcopy(anchor_features)
            features.append(next_feature)
            algorithm_definition = self._build_algorighm_definition(name_suffix=name, features=features)
            result = self.hotvect.run_all(algorithm_definition)
            results[name] = {
                'features': result['algorithm_definition']['vectorizer_parameters']['features'],
                'evaluate': result['evaluate'],
                'predict_throughput': result['predict']['mean_throughput'],
            }
        return results

    def run(self):
        candidates = copy.deepcopy(self.candidate_features)
        anchors = copy.deepcopy(self.anchor_features)
        with open(self.result_path, 'w') as f:
            idx = 0
            while True:
                candidates = [x for x in candidates if x not in anchors]
                if not candidates:
                    print('Finished')
                    break
                print(f'anchor:{anchors},\n\n candidates={candidates}')
                result = self.advance(anchor_name=f'{idx}', anchor_features=anchors, candidate_features=candidates)
                highest = sorted(result.values(), key=lambda x: x['evaluate']['mean_auc'], reverse=True)[0]
                selected = highest['features']
                f.write(json.dumps(highest))
                f.write('\n')
                f.flush()
                anchors = selected
                idx += 1


def extract_important(result):
    def extract(v):
        return {
            'features': v['algorithm_definition']['vectorizer_parameters']['features'],
            'evaluate': v['evaluate'],
            'predict_throughput': v['predict']['mean_throughput'],
        }

    ret = {name: extract(v) for name, v in result.items()}
    return ret
