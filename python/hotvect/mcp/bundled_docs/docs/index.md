---
title: Hotvect
description: Build versioned decision systems from request handling, features, models, and selection rules; run them from training through real-time or batch execution.
hide:
  - navigation
  - toc
  - footer
---

<!-- Bundled-doc resource title (the visible page title is the hero heading below):
# Hotvect
-->

<div class="hv-home">

<section class="hv-band hv-hero">
  <div class="hv-band__inner hv-hero__grid">
    <div class="hv-hero__copy">
      <a class="hv-announcement" href="whats-new/v10/">
        <span class="hv-announcement__badge">Hotvect 10</span>
        <span>Decision systems, from training to serving</span>
        <span aria-hidden="true">→</span>
      </a>
      <h1>Build the decision system. <span>Run it from training to serving.</span></h1>
      <p class="hv-hero__lead">Hotvect lets you implement request handling, feature computation, model integration, reusable components, and ranking or selection logic as one versioned algorithm. Use CatBoost, TensorFlow, PyTorch, etc., or integrate another ML library; Hotvect keeps the implementation, configuration, and parameters connected across offline training and evaluation and application serving.</p>
      <div class="hv-hero__actions">
        <a class="hv-action hv-action--primary" href="guides/first-run/">Run the product example <span aria-hidden="true">→</span></a>
        <a class="hv-action hv-action--secondary" href="concepts/">Understand Hotvect</a>
      </div>
      <a class="hv-hero__agent-link" href="agents/">Using a coding agent? Open the exact interfaces and runbooks →</a>
    </div>

    <div class="hv-workflow-panel" aria-label="Hotvect connects offline preparation to online execution through algorithm and parameter packages">
      <div class="hv-workflow-panel__bar">
        <span><i></i> one decision system</span>
        <strong>offline → online</strong>
      </div>
      <div class="hv-workflow-panel__body">
        <div class="hv-shared-contract">
          <span>shared implementation</span>
          <strong>algorithm package</strong>
          <small>definition · code · packaged assets</small>
        </div>
        <div class="hv-lifecycle">
          <section class="hv-lifecycle__lane hv-lifecycle__lane--offline">
            <header><span>OFFLINE</span><strong>Prepare</strong></header>
            <ol>
              <li><strong>Read examples and data</strong><small>recorded input</small></li>
              <li><strong>Transform and encode</strong><small>shared feature logic</small></li>
              <li><strong>Train or generate parameters</strong><small>model or runtime data</small></li>
              <li><strong>Validate and package</strong><small>versioned parameters</small></li>
            </ol>
          </section>
          <section class="hv-lifecycle__lane hv-lifecycle__lane--execution">
            <header><span>ONLINE</span><strong>Execute</strong></header>
            <ol>
              <li><strong>Load the selected version</strong><small>algorithm + parameters</small></li>
              <li><strong>Receive the request</strong><small>typed application input</small></li>
              <li><strong>Compute the decision</strong><small>features · model · dependencies</small></li>
              <li><strong>Return the result</strong><small>ranked, selected, or scored</small></li>
            </ol>
          </section>
          <div class="hv-lifecycle__handoff">
            <span><small>offline output</small><strong>parameter package</strong></span>
            <i aria-hidden="true">→</i>
            <span><small>online runtime</small><strong>algorithm + parameter packages</strong></span>
          </div>
        </div>
      </div>
      <div class="hv-workflow-panel__command"><span>handoff</span> offline preparation produces the parameters used online</div>
    </div>
  </div>
</section>

<section class="hv-band hv-proof">
  <div class="hv-band__inner hv-proof__grid">
    <div><strong>Complete decision system</strong><span>versioned executable unit</span></div>
    <div><strong>Reusable components</strong><span>declared dependencies</span></div>
    <div><strong>Offline + online</strong><span>one versioned system</span></div>
    <div><strong>Bring your ML library</strong><span>CatBoost · TensorFlow · PyTorch · etc.</span></div>
  </div>
</section>

<section class="hv-band hv-section">
  <div class="hv-band__inner hv-split">
    <div class="hv-section__copy">
      <div class="hv-eyebrow">The unit is a decision system</div>
      <h2>Package decision logic as a complete system—not just a model endpoint.</h2>
      <p>In Hotvect, a decision system is called an <strong>algorithm</strong>. It can combine request decoding, feature computation, child algorithms, model inference, and the rule that ranks or selects a result. Its <strong>algorithm package</strong> contains the implementation and definition; an optional <strong>parameter package</strong> carries trained models or generated runtime data.</p>
      <p>The public Java/JVM interfaces define how an application calls an algorithm. Model work does not have to stay in the JVM: developer-defined training commands can use the chosen Python ML stack, while inference connects through built-in or algorithm-owned runtime integrations.</p>
      <ul class="hv-check-list">
        <li>Choose an input/output interface: rank items, score candidates, or select a Top K.</li>
        <li>Connect child algorithms through declared dependencies.</li>
        <li>Keep implementation separate from trained or generated state while preserving one public interface.</li>
      </ul>
      <a class="hv-text-link" href="concepts/">Explore the decision-system model →</a>
    </div>

    <div class="hv-code-card" aria-label="Selected fields from the example product scorer definition">
      <div class="hv-code-card__bar"><span>example-product-scorer-algorithm-definition.json</span><span>ABBREVIATED</span></div>
      <pre><code>{
  <span class="hv-code-accent">"hotvect_version"</span>: "10.43.1",
  <span class="hv-code-accent">"algorithm_name"</span>: "example-product-scorer",
  <span class="hv-code-accent">"algorithm_version"</span>: "1.2.3",
  <span class="hv-code-accent">"decoder_factory_classname"</span>: "com.hotvect.example.product.ProductRankingDecoderFactory",
  <span class="hv-code-accent">"transformer_factory_classname"</span>: "com.hotvect.example.product.ProductTransformerFactory",
  <span class="hv-code-accent">"reward_function_factory_classname"</span>: "com.hotvect.example.product.ProductRewardFunctionFactory",
  <span class="hv-code-accent">"encoder_factory_classname"</span>: "com.hotvect.catboost.CatBoostStreamingEncoderFactory",
  <span class="hv-code-accent">"algorithm_factory_classname"</span>: "com.hotvect.catboost.CatBoostStreamingBulkScorerFactory",
  <span class="hv-code-accent">"train_data_spec"</span>: { "data_prefix": "example_product_examples" },
  <span class="hv-code-accent">"test_data_spec"</span>: { "data_prefix": "example_product_examples" },
  <span class="hv-code-accent">"number_of_training_days"</span>: 2,
  <span class="hv-code-accent">"training_lag_days"</span>: 1,
  <span class="hv-code-accent">"transformer_parameters"</span>: {
    "features": [
      { "name": "candidate_category", "type": "categorical" },
      { "name": "query_title_overlap", "type": "numerical" },
      { "name": "preferred_category_match", "type": "numerical" },
      { "name": "budget_fit", "type": "numerical" },
      { "name": "popularity", "type": "numerical" },
      { "name": "novelty", "type": "numerical" }
    ]
  },
  <span class="hv-code-accent">"catboost_options"</span>: {
    "loss_function": "Logloss", "iterations": 24, "depth": 3
  }
}</code></pre>
      <div class="hv-code-card__modes">
        <span>offline preparation</span>
        <i aria-hidden="true">↔</i>
        <span>runtime scoring</span>
      </div>
    </div>
  </div>
</section>

<section class="hv-band hv-section hv-section--tint">
  <div class="hv-band__inner">
    <div class="hv-section-heading">
      <div class="hv-eyebrow">Designed around the work</div>
      <h2>Choose the path that matches your job.</h2>
      <p>Hotvect gives decision-system developers, researchers, and platform engineers different entry points into the same executable system.</p>
    </div>
    <div class="hv-audience-grid">
      <a class="hv-audience-card" href="guides/develop-algorithms/">
        <span class="hv-card-index">01</span>
        <h3>Decision-system developers</h3>
        <p>Combine features, reusable components, inference, and selection rules behind one public interface.</p>
        <strong>Develop a decision system →</strong>
      </a>
      <a class="hv-audience-card" href="guides/serve-and-integrate/">
        <span class="hv-card-index">02</span>
        <h3>ML platform engineers</h3>
        <p>Integrate Java/JVM and Python execution, load versioned packages, and operate local or remote workflows.</p>
        <strong>Serve and integrate →</strong>
      </a>
      <a class="hv-audience-card" href="guides/validate-and-investigate/">
        <span class="hv-card-index">03</span>
        <h3>Applied researchers</h3>
        <p>Train, backtest, compare, and investigate changes while retaining the packages and outputs behind a result.</p>
        <strong>Validate and investigate →</strong>
      </a>
    </div>
  </div>
</section>

<section class="hv-band hv-section">
  <div class="hv-band__inner">
    <div class="hv-section-heading">
      <div class="hv-eyebrow">One system from training to serving</div>
      <h2>Change the decision system without losing its context.</h2>
      <p>The useful unit is not only a model. Hotvect keeps component connections, effective configuration, saved state, selected runtime identity, and evaluation outputs connected as the system evolves.</p>
    </div>
    <div class="hv-capability-grid">
      <a class="hv-capability-card" href="concepts/complete-algorithm/">
        <span class="hv-capability-card__mark">◎</span>
        <h3>Complete decision logic</h3>
        <p>Combine transformation, inference, ranking, selection, and generated state behind an algorithm contract.</p>
        <strong>APPLICATION</strong>
      </a>
      <a class="hv-capability-card" href="concepts/feature-computation/">
        <span class="hv-capability-card__mark">ƒ</span>
        <h3>Typed feature computation</h3>
        <p>Generate transformer code from typed feature methods, explicit dependencies, and backend-aware feature contracts.</p>
        <strong>FEATURES</strong>
      </a>
      <a class="hv-capability-card" href="concepts/dependencies-and-bindings/">
        <span class="hv-capability-card__mark">⌘</span>
        <h3>Reusable components</h3>
        <p>Build larger systems from child algorithms while keeping ownership and configuration explicit.</p>
        <strong>DEPENDENCIES</strong>
      </a>
      <a class="hv-capability-card" href="concepts/complete-algorithm/#python-and-model-libraries">
        <span class="hv-capability-card__mark">↔</span>
        <h3>Python training and inference</h3>
        <p>Use developer-chosen Python libraries for training, then connect inference through built-in or algorithm-owned runtime integrations.</p>
        <strong>ML RUNTIMES</strong>
      </a>
      <a class="hv-capability-card" href="concepts/artifacts-and-identity/">
        <span class="hv-capability-card__mark">#</span>
        <h3>Versioned runtime packages</h3>
        <p>Keep implementation, effective definition, trained parameters, and loaded runtime identity distinguishable and inspectable.</p>
        <strong>ARTIFACTS</strong>
      </a>
      <a class="hv-capability-card" href="concepts/configuration-and-experimentation/">
        <span class="hv-capability-card__mark">%</span>
        <h3>Configuration and experiments</h3>
        <p>Connect each evaluated configuration and parameter package to the runtime version selected for an experiment.</p>
        <strong>CONFIGURATION</strong>
      </a>
      <a class="hv-capability-card" href="guides/pipeline-stages/">
        <span class="hv-capability-card__mark">◇</span>
        <h3>Traceable workflow</h3>
        <p>Follow saved state, encoded data, parameters, predictions, and evaluation outputs.</p>
        <strong>OUTPUTS</strong>
      </a>
      <a class="hv-capability-card" href="guides/local-backtest/">
        <span class="hv-capability-card__mark">⇄</span>
        <h3>Controlled backtests</h3>
        <p>Compare revisions with explicit inputs, output locations, and success checks.</p>
        <strong>COMPARISON</strong>
      </a>
      <a class="hv-capability-card" href="guides/local-algorithm-debugging/">
        <span class="hv-capability-card__mark">▣</span>
        <h3>Local inspection</h3>
        <p>Load complete runtime packages, submit recorded examples, and compare algorithm versions in the browser UI.</p>
        <strong>DEBUGGING</strong>
      </a>
    </div>
  </div>
</section>

<section class="hv-band hv-section hv-section--tint">
  <div class="hv-band__inner hv-split">
    <div class="hv-section__copy">
      <div class="hv-eyebrow">Configuration and experimentation</div>
      <h2>Know what changed and what reached an experiment.</h2>
      <p>Hotvect keeps the evaluated configuration, trained parameters, released runtime, and experiment results connected. This makes a candidate traceable from offline evidence to the version that handles traffic.</p>
      <a class="hv-text-link" href="concepts/configuration-and-experimentation/">Understand configuration and experimentation →</a>
      <div class="hv-boundary-note">
        <strong>Current boundary</strong>
        <p>Hotvect currently integrates with an external experiment control plane. Bringing that control plane into Hotvect is the direction, not a capability of the current release.</p>
      </div>
    </div>

    <div class="hv-workflow-panel" aria-label="A candidate moves from configuration through evidence and release to an experiment">
      <div class="hv-workflow-panel__bar">
        <span><i></i> one traceable candidate</span>
        <strong>offline → experiment</strong>
      </div>
      <div class="hv-workflow-panel__body">
        <div class="hv-lifecycle__lane">
          <header><span>LIFECYCLE</span><strong>From change to evidence</strong></header>
          <ol>
            <li><strong>Change the decision system</strong><small>implementation or configuration</small></li>
            <li><strong>Train and compare</strong><small>retain packages, outputs, and metrics</small></li>
            <li><strong>Release the accepted version</strong><small>exact implementation and parameters</small></li>
            <li><strong>Assign and observe</strong><small>connect traffic and results to that version</small></li>
          </ol>
        </div>
      </div>
      <div class="hv-workflow-panel__command"><span>identity</span> evidence and traffic refer to the same released runtime</div>
    </div>
  </div>
</section>

<section class="hv-band hv-section">
  <div class="hv-band__inner hv-paths-layout">
    <div class="hv-section__copy">
      <div class="hv-eyebrow">Learn by following one package</div>
      <h2>Take one decision system from source to a selected, validated runtime.</h2>
      <p>Start with its public input/output interface, learn which components it uses, then follow its configuration and packages through preparation, evaluation, and runtime selection.</p>
      <div class="hv-boundary-note">
        <strong>Direction: keep one logical graph even when components run in different places</strong>
        <p>Today, Hotvect connects JVM algorithms and managed Python workers. The component model is intended to preserve the same logical system as work moves to specialized processes or services, while keeping transport and failure behavior explicit. <a href="architecture/status-and-direction/">See current status and direction →</a></p>
      </div>
    </div>
    <div class="hv-path-list">
      <a href="concepts/complete-algorithm/"><span>01</span><div><strong>Decision-system model</strong><small>Understand the executable unit, interfaces, and dependencies.</small></div><i>→</i></a>
      <a href="guides/example-product-algorithms/"><span>02</span><div><strong>Explore a complete example</strong><small>Train a scorer, compose Ranker and TopK, and inspect the result.</small></div><i>→</i></a>
      <a href="guides/develop-algorithms/"><span>03</span><div><strong>Develop a decision system</strong><small>Build the implementation package and validate its public interface.</small></div><i>→</i></a>
      <a href="guides/local-backtest/"><span>04</span><div><strong>Run a backtest</strong><small>Compare a revision with explicit inputs, outputs, and checks.</small></div><i>→</i></a>
      <a href="concepts/configuration-and-experimentation/"><span>05</span><div><strong>Understand experiment releases</strong><small>Connect evaluated packages to selected runtimes and results.</small></div><i>→</i></a>
    </div>
  </div>
</section>

<section class="hv-band hv-final-cta">
  <div class="hv-band__inner">
    <div>
      <div class="hv-eyebrow">Start with the complete system</div>
      <h2>Build one decision system you can run and inspect.</h2>
      <p>Connect the behavior, configuration, trained state, experiment assignment, and evidence behind each runtime version.</p>
    </div>
    <div class="hv-final-cta__actions">
      <a class="hv-action hv-action--light" href="guides/first-run/">Run the product example →</a>
      <a class="hv-action hv-action--ghost" href="architecture/">Understand the architecture</a>
    </div>
  </div>
</section>

<footer class="hv-band hv-home-footer">
  <div class="hv-band__inner">
    <div class="hv-home-footer__brand"><img src="hotvect-pepper.svg" alt=""><strong>Hotvect</strong><span>Framework and runtime for versioned decision systems.</span></div>
    <div class="hv-home-footer__links">
      <a href="guides/">Documentation</a>
      <a href="agents/">Agent workflow</a>
      <a href="reference/cli/">CLI reference</a>
      <a href="https://github.com/zalando/hotvect">GitHub</a>
    </div>
  </div>
</footer>

</div>
