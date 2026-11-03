"""Reference values for LyumjevPdCurveTest.

A line-by-line transcription of Boost 6.1.2 InsulinLyumjevPDBasePlugin.iobCalcForTreatment
written independently of the Kotlin port so that the
pinned literals in the test do not come from the code they check.

6.1.2 received the amount in pump units. The peak-time dose X is the insulin delivered: the amount
itself on U100 and twice the amount on U200 (Boost 7.4.5, audit #23; 6.1.2 had the two swapped).
Run with: python3 -I lyumjev_pd_reference.py
"""
import math

A0, A1, B1 = 61.33, 12.27, 0.05185
END = 8.0 * 60.0


def pd(amount, eff, t):
    tp = (A0 + A1 * eff) / (1 + B1 * eff)
    tpm = tp ** 2.0 * 2
    act = (2 * amount / tpm) * t * math.exp(-t ** 2.0 / tpm)
    iob = amount * (math.exp(-t ** 2.0 / tpm) - math.exp(-END ** 2.0 / tpm))
    return tp, act, iob


if __name__ == "__main__":
    cases = [
        ("U100 1 U", 1.0, 1.0),
        ("U100 5 U", 5.0, 5.0),
        ("U100 20 U", 20.0, 20.0),
        ("U200 2.5 pump units (5 IU)", 2.5, 2.0 * 2.5),
    ]
    for label, amount, eff in cases:
        for t in [0.0, 30.0, 60.0, 120.0, 240.0, 479.0]:
            tp, act, iob = pd(amount, eff, t)
            print(f"{label:30s} t={t:5.0f}  tp={tp!r}  activity={act!r}  iob={iob!r}")
