import { animate, motion, useMotionValue, useReducedMotion, useTransform } from "framer-motion";
import { useEffect, useState } from "react";

export const pageMotion = {
  initial: { opacity: 0, y: 12 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: 0.3, ease: "easeOut" },
};

export const staggerContainer = {
  animate: {
    transition: {
      staggerChildren: 0.055,
      delayChildren: 0.04,
    },
  },
};

export const cardMotion = {
  initial: { opacity: 0, y: 14 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: 0.28, ease: "easeOut" },
  whileHover: { y: -4, transition: { duration: 0.18 } },
  whileTap: { scale: 0.99 },
};

export function MotionPage({ children, className = "", ...props }) {
  const reduce = useReducedMotion();
  return (
    <motion.div className={className} {...(reduce ? {} : pageMotion)} {...props}>
      {children}
    </motion.div>
  );
}

export function MotionCard({ as: Component = "section", className = "", children, interactive = true, ...props }) {
  const reduce = useReducedMotion();
  const motionProps = reduce ? {} : {
    ...cardMotion,
    whileHover: interactive ? cardMotion.whileHover : undefined,
    whileTap: interactive ? cardMotion.whileTap : undefined,
  };
  const MotionComponent = motion(Component);
  return (
    <MotionComponent className={className} {...motionProps} {...props}>
      {children}
    </MotionComponent>
  );
}

export function AnimatedNumber({ value, className = "", fallback = "Unavailable" }) {
  const numeric = Number(value);
  const isNumber = Number.isFinite(numeric);
  const motionValue = useMotionValue(0);
  const rounded = useTransform(motionValue, (latest) => Math.round(latest));
  const [display, setDisplay] = useState(isNumber ? 0 : fallback);
  const reduce = useReducedMotion();

  useEffect(() => {
    if (!isNumber) {
      setDisplay(fallback);
      return undefined;
    }
    if (reduce) {
      setDisplay(Math.round(numeric));
      return undefined;
    }
    const unsubscribe = rounded.on("change", setDisplay);
    const controls = animate(motionValue, numeric, { duration: 0.75, ease: "easeOut" });
    return () => {
      unsubscribe();
      controls.stop();
    };
  }, [fallback, isNumber, motionValue, numeric, reduce, rounded]);

  return <span className={className}>{display}</span>;
}

export { motion };
